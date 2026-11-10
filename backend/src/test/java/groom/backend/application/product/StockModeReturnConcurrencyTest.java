package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.product.StockLedgerService.DbReservation;
import groom.backend.infrastructure.product.StockWarmUpRunner;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// DB 모드 예약(공유 advisory 잠금)과 REDIS 복귀(배타 advisory 잠금)가 서로를 기다려 직렬화되는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false"
})
@DisplayName("복귀 ↔ DB 모드 예약 직렬화")
class StockModeReturnConcurrencyTest {

    private static final long MIN_DWELL_MS = 1000;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private StockLedgerService stockLedgerService;
    @Autowired
    private StockLedgerRepository stockLedgerRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID productId;
    private UUID paymentId;

    @BeforeEach
    void setUp() {
        productId = productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name("return-concurrency")
                .price(1000).stock(10).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(true).build()).getId();
        paymentId = UUID.randomUUID();
        // 대기 시간이 지난 DB 모드 상태
        jdbc.update("UPDATE product SET stock_mode = 'DB', stock_mode_changed_at = now() - interval '1 hour' WHERE id = ?", productId);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(key());
        jdbc.update("DELETE FROM stock_ledger WHERE product_id = ?", productId);
        productRepository.deleteById(productId);
    }

    private String key() {
        return ProductStockRedisRepository.STOCK_KEY_PREFIX + productId;
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId);
    }

    private int unapplied() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM stock_ledger WHERE product_id = ? AND applied = false",
                Integer.class, productId);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("DB 모드 차감을 마친 트랜잭션이 열려 있는 동안 복귀는 끝나지 않고, 커밋되면 끝나며 키는 stock − 미적용 합이다")
    void returnToRedis_DB모드_예약_트랜잭션이_열려있으면_기다렸다가_키는_stock_빼기_미적용합이다() throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reservation = pool.submit(() -> transactionTemplate.execute(status -> {
                assertThat(stockLedgerService.reserveInDb(paymentId, productId, 3)).isEqualTo(DbReservation.RESERVED);
                reserved.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> returning = pool.submit(() -> stockLedgerService.returnToRedis(productId, MIN_DWELL_MS));

            Thread.sleep(500);
            assertThat(returning.isDone()).as("예약 트랜잭션이 열려 있는 동안 복귀는 끝나지 않는다").isFalse();
            assertThat(redisTemplate.hasKey(key())).as("복귀가 키를 아직 쓰지 않았다").isFalse();

            release.countDown();
            reservation.get(10, TimeUnit.SECONDS);
            assertThat(returning.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(stock()).as("예약이 커밋돼 재고에 반영됐다").isEqualTo(7);
        assertThat(redisTemplate.opsForValue().get(key())).isEqualTo(String.valueOf(stock() - unapplied()));
        assertThat(jdbc.queryForObject("SELECT stock_mode FROM product WHERE id = ?", String.class, productId)).isEqualTo("REDIS");
    }

    @Test
    @DisplayName("복귀가 커밋된 뒤 시작한 DB 모드 예약은 MODE_FLIPPED 이고 원장 행도 재고 변경도 없다")
    void reserveInDb_복귀가_커밋된_뒤에는_MODE_FLIPPED_원장_행_없고_재고는_그대로다() {
        assertThat(stockLedgerService.returnToRedis(productId, MIN_DWELL_MS)).isTrue();
        int stockBefore = stock();

        DbReservation result = stockLedgerService.reserveInDb(paymentId, productId, 3);

        assertThat(result).isEqualTo(DbReservation.MODE_FLIPPED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_ledger WHERE payment_id = ?", Integer.class, paymentId)).isZero();
        assertThat(stock()).isEqualTo(stockBefore);
    }
}
