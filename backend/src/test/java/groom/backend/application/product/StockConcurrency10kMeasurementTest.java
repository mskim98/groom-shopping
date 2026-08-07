package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.common.exception.BusinessException;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.vo.Description;
import groom.backend.domain.product.model.vo.Name;
import groom.backend.domain.product.model.vo.Price;
import groom.backend.domain.product.model.vo.Stock;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 재고 차감 1만 건 정합성 측정 (실 PostgreSQL 필요).
 *
 * <p>포트폴리오에 기재된 "재고 10,000 / 동시 차감 1만 건" 조건을 재현해
 * 완주율(성공 건수)과 정합성(드리프트)을 분리 측정한다
 *
 * <p>기존 ProductStockConcurrencyIntegrationTest 는 100건 규모이며 정합성만 검증한다
 * 이 테스트는 실패 사유를 코드별로 분류해 재시도 소진과 그 외를 구분하는 것이 목적이다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("재고 차감 1만 건 정합성 측정")
class StockConcurrency10kMeasurementTest {

    private static final int INITIAL_STOCK = 10_000;
    private static final int TOTAL_REQUESTS = 10_000;
    // k6 stock-concurrency-test.js 의 VUS 기본값과 맞춘다
    private static final int CONCURRENCY = 200;

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
    @DisplayName("재고 10,000 에 1만 건 동시 차감 — 완주율과 드리프트를 분리 측정")
    void measure() throws InterruptedException {
        // given
        productId = UUID.randomUUID();
        productRepository.save(Product.create(
                productId, new Name("1만건측정상품"), new Description("desc"),
                new Price(1000), new Stock(INITIAL_STOCK), ProductCategory.GENERAL, 10, true, null));

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(TOTAL_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TOTAL_REQUESTS);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        // 실패 사유별 분류 -> "재시도 소진"과 그 외를 구분하지 못하면 원인이 추정에 머문다
        Map<String, AtomicInteger> failureByReason = new ConcurrentHashMap<>();
        AtomicLong totalLatencyNanos = new AtomicLong();

        // when
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    // 전 스레드를 같은 시점에 풀어 경합을 최대화
                    start.await();
                    long t0 = System.nanoTime();
                    productStockService.decreaseWithOptimisticLock(productId, 1);
                    totalLatencyNanos.addAndGet(System.nanoTime() - t0);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    failed.incrementAndGet();
                    failureByReason.computeIfAbsent(e.getErrorCode().name(), k -> new AtomicInteger())
                            .incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    failureByReason.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger())
                            .incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(60, TimeUnit.SECONDS);
        long wallStart = System.nanoTime();
        start.countDown();
        boolean finished = done.await(15, TimeUnit.MINUTES);
        long wallMillis = (System.nanoTime() - wallStart) / 1_000_000;
        executor.shutdown();

        // then
        int finalStock = productRepository.findById(productId).orElseThrow().getStock();
        int drift = finalStock - (INITIAL_STOCK - success.get());

        System.out.println("========== 재고 차감 1만 건 측정 결과 ==========");
        System.out.printf("초기 재고        : %d%n", INITIAL_STOCK);
        System.out.printf("총 시도          : %d (동시성 %d)%n", TOTAL_REQUESTS, CONCURRENCY);
        System.out.printf("성공             : %d (%.2f%%)%n",
                success.get(), success.get() * 100.0 / TOTAL_REQUESTS);
        System.out.printf("실패             : %d%n", failed.get());
        failureByReason.forEach((reason, count) ->
                System.out.printf("  └ %-28s : %d%n", reason, count.get()));
        System.out.printf("최종 DB 재고     : %d%n", finalStock);
        System.out.printf("기대 재고        : %d%n", INITIAL_STOCK - success.get());
        System.out.printf("드리프트         : %d%n", drift);
        System.out.printf("전체 소요        : %d ms%n", wallMillis);
        System.out.printf("성공 평균 지연   : %.2f ms%n",
                success.get() == 0 ? 0.0 : totalLatencyNanos.get() / 1_000_000.0 / success.get());
        System.out.println("=============================================");

        assertThat(finished).as("15분 내 완료").isTrue();
        assertThat(success.get() + failed.get()).isEqualTo(TOTAL_REQUESTS);
        // 정합성: 성공한 차감만 정확히 반영됐는가 (Lost Update 부재)
        assertThat(drift).as("DB 재고 드리프트").isZero();
        assertThat(finalStock).isGreaterThanOrEqualTo(0);
    }
}
