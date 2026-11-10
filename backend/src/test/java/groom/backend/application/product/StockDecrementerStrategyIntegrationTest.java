package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
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

/**
 * 플래시 세일이 아닌 상품의 재고 차감(조건부 UPDATE)이 동시 경합에서 초과판매 0 을 지키는지 확인한다.
 * 낙관적 락 · 비관적 락 전략은 없어졌고 이 경로 하나가 남았다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("재고 차감(조건부 UPDATE)")
class StockDecrementerStrategyIntegrationTest {

    private static final int INITIAL_STOCK = 100;
    private static final int THREADS = 50;

    @Autowired
    private ConditionalUpdateStockDecrementer decrementer;
    @Autowired
    private SpringDataProductRepository springDataProductRepository;

    private UUID productId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        springDataProductRepository.save(ProductJpaEntity.builder()
                .id(productId).name("decrement").description("d").price(1000).stock(INITIAL_STOCK)
                .isActive(true).category("GENERAL").status("AVAILABLE").thresholdValue(10).build());
    }

    @AfterEach
    void tearDown() {
        springDataProductRepository.deleteById(productId);
    }

    @Test
    @DisplayName("동시 차감에서 초과판매가 생기지 않고 최종 재고는 초기 재고에서 성공 건수를 뺀 값이다")
    void concurrentDecrease_noOversell() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch latch = new CountDownLatch(THREADS * 2);
        AtomicInteger succeeded = new AtomicInteger();
        // 재고 100 에 100 건을 시도한다(스레드 50 x 2) - 전부 성공해야 한다
        for (int i = 0; i < THREADS * 2; i++) {
            pool.submit(() -> {
                try {
                    decrementer.decrease(productId, 1);
                    succeeded.incrementAndGet();
                } catch (RuntimeException ignored) {
                    // 재고 부족은 정상 실패
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        int finalStock = springDataProductRepository.findById(productId).orElseThrow().getStock();
        assertThat(finalStock).as("최종재고 == 초기재고 - 성공건수").isEqualTo(INITIAL_STOCK - succeeded.get());
        assertThat(finalStock).as("음수 재고 없음").isGreaterThanOrEqualTo(0);
        assertThat(succeeded.get()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    @DisplayName("재고보다 많이 시도하면 재고만큼만 성공하고 나머지는 재고 부족으로 실패한다")
    void decreaseBeyondStock_failsWithoutNegativeStock() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        int attempts = INITIAL_STOCK + 50;
        CountDownLatch latch = new CountDownLatch(attempts);
        AtomicInteger succeeded = new AtomicInteger();
        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    decrementer.decrease(productId, 1);
                    succeeded.incrementAndGet();
                } catch (RuntimeException ignored) {
                    // 재고 부족
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(succeeded.get()).isEqualTo(INITIAL_STOCK);
        assertThat(springDataProductRepository.findById(productId).orElseThrow().getStock()).isZero();
    }
}
