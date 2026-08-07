package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.vo.Description;
import groom.backend.domain.product.model.vo.Name;
import groom.backend.domain.product.model.vo.Price;
import groom.backend.domain.product.model.vo.Stock;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ① 재고 차감 동시성 통합 테스트 (실 PostgreSQL 필요).
 *
 * <p>동시 차감에서 @Version 낙관적 락이 Lost Update 를 차단하는지 검증한다.
 * 동시성 검증이라 클래스 레벨 @Transactional 을 쓰지 않는다(각 스레드가 독립 커밋해야 함).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("재고 차감 동시성 통합 테스트")
class ProductStockConcurrencyIntegrationTest {

    @Autowired
    private ProductStockService productStockService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SpringDataProductRepository springDataProductRepository;

    private UUID productId;

    @AfterEach
    void tearDown() {
        if (productId != null) {
            springDataProductRepository.deleteById(productId);
        }
    }

    @Test
    @DisplayName("동시 100건 차감 시 성공 건수만큼만 재고가 줄어 Lost Update 가 없다")
    void concurrentDecrease_noLostUpdate() throws InterruptedException {
        // given - 재고 100 상품 생성
        productId = UUID.randomUUID();
        int initialStock = 100;
        int threadCount = 100;
        productRepository.save(Product.create(
                productId, new Name("동시성테스트상품"), new Description("desc"),
                new Price(1000), new Stock(initialStock), ProductCategory.GENERAL, 10, true, null));

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        // when - 100개 스레드가 동시에 1개씩 차감
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    productStockService.decreaseWithOptimisticLock(productId, 1);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then - 핵심: 최종 재고 == 초기 재고 - 성공 건수 (성공한 차감만 정확히 반영 = Lost Update 없음)
        int finalStock = productRepository.findById(productId).orElseThrow().getStock();
        assertThat(success.get() + failed.get()).isEqualTo(threadCount);
        assertThat(finalStock).isEqualTo(initialStock - success.get());
        assertThat(finalStock).isGreaterThanOrEqualTo(0);
    }
}
