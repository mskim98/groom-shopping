package groom.backend.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import groom.backend.application.product.StockLedgerRepository;
import groom.backend.application.product.StockLedgerService;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// 원장 적용 배치가 미적용 행을 SKIP LOCKED 로 집어 재고를 낮추고 같은 트랜잭션에서 적용 표시를 하는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("원장 적용 배치")
class StockLedgerApplyJobTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;
    @SpyBean
    private StockLedgerRepository stockLedgerRepository;

    @Autowired
    private StockLedgerApplyJob job;
    @Autowired
    private StockLedgerService stockLedgerService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID productId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        reset(stockLedgerRepository);
        jdbc.update("DELETE FROM stock_ledger WHERE product_id = ?", productId);
        productRepository.deleteById(productId);
    }

    private void product(int stock) {
        productRepository.save(ProductJpaEntity.builder().id(productId).name("ledger-job").price(1000)
                .stock(stock).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(true).build());
    }

    private void ledger(String kind, int qty) {
        jdbc.update("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) VALUES (?, ?, ?, ?, false)",
                UUID.randomUUID(), productId, qty, kind);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId);
    }

    private int unappliedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_ledger WHERE product_id = ? AND applied = false",
                Integer.class, productId);
    }

    private int remaining() {
        return stock() - jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM stock_ledger WHERE product_id = ? AND applied = false",
                Integer.class, productId);
    }

    @Test
    @DisplayName("재고 10 에 미적용 SALE 3 이면 적용 뒤 재고 7 · 미적용 0 이다")
    void applyOnce_재고10_SALE3_재고7_미적용0() {
        product(10);
        ledger("SALE", 3);

        job.applyOnce();

        assertThat(stock()).isEqualTo(7);
        assertThat(unappliedCount()).isZero();
    }

    @Test
    @DisplayName("두 번째 호출은 아무것도 바꾸지 않는다")
    void applyOnce_두번째_호출은_변화가_없다() {
        product(10);
        ledger("SALE", 3);
        job.applyOnce();

        job.applyOnce();

        assertThat(stock()).isEqualTo(7);
        assertThat(unappliedCount()).isZero();
    }

    @Test
    @DisplayName("재고 − 미적용 합은 적용 전과 후가 같다")
    void applyOnce_재고에서_미적용합을_뺀_값이_적용전후_같다() {
        product(10);
        ledger("SALE", 3);
        ledger("SALE", 2);
        ledger("REVERSAL", -2);
        int before = remaining();

        job.applyOnce();

        assertThat(before).isEqualTo(7);
        assertThat(remaining()).isEqualTo(before);
        assertThat(stock()).isEqualTo(7);
    }

    @Test
    @DisplayName("재고 갱신이 예외를 던지면 행은 미적용으로 남고 재고도 그대로다")
    void applyOnce_재고갱신이_예외면_행은_미적용으로_남는다() {
        product(10);
        ledger("SALE", 3);
        doThrow(new IllegalStateException("stock update down")).when(stockLedgerRepository).lowerStock(any(), anyInt());

        job.applyOnce();

        assertThat(unappliedCount()).isEqualTo(1);
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    @DisplayName("두 호출이 동시에 돌아도 각 행은 한 번만 적용된다")
    void applyOnce_동시_두호출_각_행은_한번만_적용된다() throws Exception {
        product(1000);
        for (int i = 0; i < 40; i++) {
            ledger("SALE", 1);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                start.await();
                return job.applyOnce();
            });
            Future<?> second = pool.submit(() -> {
                start.await();
                return job.applyOnce();
            });
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(stock()).isEqualTo(960);
        assertThat(unappliedCount()).isZero();
    }

    @Test
    @DisplayName("미적용 SALE 합이 재고보다 크면 그 상품만 실패하고 재고 · 행은 그대로이며 같은 회차의 다른 상품은 적용된다 (Ruling R117-3)")
    void applyOnce_미적용합이_재고보다_크면_그_상품만_실패하고_다른_상품은_적용된다() {
        UUID other = UUID.randomUUID();
        productRepository.save(ProductJpaEntity.builder().id(other).name("ledger-job-other").price(1000)
                .stock(10).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(true).build());
        try {
            product(2);
            ledger("SALE", 3);
            jdbc.update("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) VALUES (?, ?, 4, 'SALE', false)",
                    UUID.randomUUID(), other);

            job.applyOnce();

            assertThat(stock()).as("실패한 상품의 재고").isEqualTo(2);
            assertThat(unappliedCount()).as("실패한 상품의 행은 미적용으로 남는다").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, other))
                    .as("다른 상품은 적용된다").isEqualTo(6);
        } finally {
            jdbc.update("DELETE FROM stock_ledger WHERE product_id = ?", other);
            productRepository.deleteById(other);
        }
    }

    @Test
    @DisplayName("다른 트랜잭션이 같은 상품의 TX1 원장 기록을 열어 둔 채여도 applyOnce 는 그 상품의 나머지 미적용 행을 적용한다 (건너뛰지도 막히지도 않는다)")
    void applyOnce_TX1이_열려_있어도_같은_상품의_나머지_행을_적용한다() throws Exception {
        product(10);
        ledger("SALE", 3);
        CountDownLatch recorded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // TX1 이 상품 P 의 SALE 을 기록하고 커밋하지 않은 채 열려 있다
            Future<?> tx1 = pool.submit(() -> transactionTemplate.execute(status -> {
                stockLedgerService.recordSale(UUID.randomUUID(), productId, 1);
                recorded.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(recorded.await(10, TimeUnit.SECONDS)).isTrue();

            pool.submit(() -> job.applyOnce()).get(10, TimeUnit.SECONDS);

            assertThat(stock()).as("커밋된 SALE 3 만 접어 넣는다").isEqualTo(7);
            release.countDown();
            tx1.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(unappliedCount()).as("TX1 이 커밋한 행은 다음 회차 몫이라 미적용으로 남는다").isEqualTo(1);
        assertThat(remaining()).as("남은 재고 = stock − 미적용 합").isEqualTo(6);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
