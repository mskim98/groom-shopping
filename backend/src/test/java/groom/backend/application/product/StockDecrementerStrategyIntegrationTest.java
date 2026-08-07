package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 세 차감 전략이 동시 경합에서 초과판매 0 을 지키는지 확인한다.
 * 전략 교체는 프로퍼티로 하지만, 여기서는 세 구현체를 모두 주입받아 직접 호출해 비교한다.
 *
 * <p>운영에서는 {@code @ConditionalOnProperty} 로 하나만 뜬다. 여기서는 나머지 둘을
 * {@code @TestConfiguration} 의 {@code @Bean} 메서드로 등록한다 —
 * 조건은 컴포넌트 스캔된 클래스에 붙는 것이라 {@code @Bean} 으로 만들면 평가되지 않고,
 * 컨테이너가 만든 빈이므로 {@code @Transactional} 프록시는 그대로 적용된다.
 * ({@code @Import} 로는 안 된다 — 조건이 그대로 평가돼 빈이 등록되지 않는다)
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("재고 차감 전략 3종")
class StockDecrementerStrategyIntegrationTest {

    @TestConfiguration
    static class AllStrategies {
        // autowireCandidate = false : PaymentApplicationService 는 StockDecrementer 를 하나만 받는다.
        // 후보로 두면 3개가 돼 NoUniqueBeanDefinitionException 으로 컨텍스트가 뜨지 않는다.
        // 이 테스트는 getBeansOfType 으로 직접 꺼내므로 후보에서 빠져도 접근할 수 있다
        @Bean(autowireCandidate = false)
        ConditionalUpdateStockDecrementer conditionalUpdateStockDecrementer(
                SpringDataProductRepository repository) {
            return new ConditionalUpdateStockDecrementer(repository);
        }

        @Bean(autowireCandidate = false)
        PessimisticLockStockDecrementer pessimisticLockStockDecrementer(
                SpringDataProductRepository repository) {
            return new PessimisticLockStockDecrementer(repository);
        }
    }

    private static final int INITIAL_STOCK = 100;
    private static final int THREADS = 50;

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private SpringDataProductRepository springDataProductRepository;

    private UUID productId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        springDataProductRepository.save(ProductJpaEntity.builder()
                .id(productId).name("strategy").description("d").price(1000).stock(INITIAL_STOCK)
                .isActive(true).category("GENERAL").status("AVAILABLE").thresholdValue(10)
                .build());
    }

    @AfterEach
    void tearDown() {
        springDataProductRepository.deleteById(productId);
    }

    @Test
    @DisplayName("세 전략 모두 등록돼 있고, 동시 차감에서 초과판매가 생기지 않는다")
    void allStrategiesPreventOversell() throws InterruptedException {
        // getBeansOfType 은 autowireCandidate 여부와 무관하게 등록된 빈을 전부 돌려준다
        List<StockDecrementer> decrementers =
                List.copyOf(applicationContext.getBeansOfType(StockDecrementer.class).values());

        assertThat(decrementers).extracting(StockDecrementer::strategyName)
                .containsExactlyInAnyOrder("optimistic", "conditional", "pessimistic");

        for (StockDecrementer decrementer : decrementers) {
            springDataProductRepository.findById(productId).ifPresent(entity -> {
                entity.setStock(INITIAL_STOCK);
                springDataProductRepository.save(entity);
            });

            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch latch = new CountDownLatch(THREADS);
            AtomicInteger succeeded = new AtomicInteger();
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        decrementer.decrease(productId, 1);
                        succeeded.incrementAndGet();
                    } catch (RuntimeException ignored) {
                        // 충돌·부족은 정상 실패
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(60, TimeUnit.SECONDS);
            pool.shutdown();

            int finalStock = springDataProductRepository.findById(productId).orElseThrow().getStock();
            assertThat(finalStock)
                    .as("%s 전략: 최종재고 == 초기재고 - 성공건수", decrementer.strategyName())
                    .isEqualTo(INITIAL_STOCK - succeeded.get());
            assertThat(finalStock).as("%s 전략: 음수 재고 없음", decrementer.strategyName())
                    .isGreaterThanOrEqualTo(0);
        }
    }
}
