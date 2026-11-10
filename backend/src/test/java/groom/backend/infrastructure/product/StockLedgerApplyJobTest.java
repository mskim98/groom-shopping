package groom.backend.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import groom.backend.application.product.StockLedgerRepository;
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
}
