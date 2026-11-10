package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import groom.backend.common.exception.BusinessException;
import groom.backend.infrastructure.product.StockModeRecoveryJob;
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

// 배타 advisory 잠금 대기에 상한(stock.mode.lock-timeout-ms)이 있어 TX1 이 오래 열려 있어도 전환 · 복귀가 영원히 매달리지 않는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "stock.mode.lock-timeout-ms=200"
})
@DisplayName("전환 · 복귀 잠금 대기 상한")
class StockModeLockTimeoutTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private StockLedgerService stockLedgerService;
    @Autowired
    private StockLedgerRepository stockLedgerRepository;
    @Autowired
    private StockModeRecoveryJob recoveryJob;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID productId;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        productId = productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name("lock-timeout")
                .price(1000).stock(10).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(true).build()).getId();
        jdbc.update("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) VALUES (?, ?, 3, 'SALE', false)",
                UUID.randomUUID(), productId);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        pool.shutdownNow();
        redisTemplate.delete(key());
        jdbc.update("DELETE FROM stock_ledger WHERE product_id = ?", productId);
        productRepository.deleteById(productId);
    }

    private String key() {
        return ProductStockRedisRepository.STOCK_KEY_PREFIX + productId;
    }

    private String mode() {
        return jdbc.queryForObject("SELECT stock_mode FROM product WHERE id = ?", String.class, productId);
    }

    // 공유 advisory 잠금을 쥔 채 열려 있는 트랜잭션(TX1 처럼)
    private void holdSharedLock() throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        pool.submit(() -> transactionTemplate.execute(status -> {
            stockLedgerRepository.lockProductShared(productId);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private void assertLockTimeoutReset() {
        for (int i = 0; i < 5; i++) {
            assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).as("풀의 커넥션에 상한이 남지 않는다").isEqualTo("0");
        }
    }

    @Test
    @DisplayName("공유 잠금이 열려 있으면 복귀는 2초 안에 포기하고 모드와 Redis 키는 그대로이며 복귀 배치는 던지지 않는다")
    void returnToRedis_공유잠금이_열려있으면_2초안에_포기하고_DB모드_키_그대로_배치는_던지지_않는다() throws Exception {
        jdbc.update("UPDATE product SET stock_mode = 'DB', stock_mode_changed_at = now() - interval '1 hour' WHERE id = ?", productId);
        redisTemplate.opsForValue().set(key(), "5");
        holdSharedLock();

        Future<Boolean> returning = pool.submit(() -> stockLedgerService.returnToRedis(productId, 1000));
        assertThat(returning.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(mode()).isEqualTo("DB");
        assertThat(redisTemplate.opsForValue().get(key())).isEqualTo("5");

        // 다른 시험이 남긴 복귀 대상이 있을 수 있어 반환 수는 보지 않고, 던지지 않고 2초 안에 끝나는지만 본다
        pool.submit(recoveryJob::recoverOnce).get(2, TimeUnit.SECONDS);
        assertThat(mode()).isEqualTo("DB");
        assertLockTimeoutReset();
    }

    @Test
    @DisplayName("공유 잠금이 열려 있으면 전환은 2초 안에 업무 예외로 포기하고 재고와 원장은 그대로다")
    void switchToDb_공유잠금이_열려있으면_2초안에_업무예외로_포기하고_재고와_원장은_그대로다() throws Exception {
        holdSharedLock();

        Future<?> switching = pool.submit(() -> stockLedgerService.switchToDb(productId));
        assertThatThrownBy(() -> switching.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(BusinessException.class);
        assertThat(mode()).isEqualTo("REDIS");
        assertThat(jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_ledger WHERE product_id = ? AND applied = false",
                Integer.class, productId)).isEqualTo(1);
        assertLockTimeoutReset();
    }
}
