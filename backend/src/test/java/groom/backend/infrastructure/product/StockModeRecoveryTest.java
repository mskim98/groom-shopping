package groom.backend.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockLedgerRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// DB 모드 상품이 대기 시간이 지나고 Redis 가 응답할 때만 REDIS 모드로 돌아오고, 키는 stock − 미적용 합으로 쓰이는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "stock.mode.min-db-dwell-ms=200"
})
@DisplayName("DB 모드 → REDIS 모드 자동 복귀")
class StockModeRecoveryTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;
    @SpyBean
    private ProductStockRedisRepository productStockRedisRepository;

    @Autowired
    private StockModeRecoveryJob job;
    @Autowired
    private StockLedgerRepository stockLedgerRepository;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID productId;

    @BeforeEach
    void setUp() {
        productId = productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name("recovery")
                .price(1000).stock(10).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(true).build()).getId();
        jdbc.update("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) VALUES (?, ?, 3, 'SALE', false)",
                UUID.randomUUID(), productId);
    }

    @AfterEach
    void tearDown() {
        reset(productStockRedisRepository);
        redisTemplate.delete(key());
        jdbc.update("DELETE FROM stock_ledger WHERE product_id = ?", productId);
        productRepository.deleteById(productId);
    }

    private String key() {
        return ProductStockRedisRepository.STOCK_KEY_PREFIX + productId;
    }

    // 대기 시간이 지난 DB 모드 상태로 만든다
    private void dbModeSince(String interval) {
        jdbc.update("UPDATE product SET stock_mode = 'DB', stock_mode_changed_at = now() - CAST(? AS interval) WHERE id = ?",
                interval, productId);
    }

    private String mode() {
        return jdbc.queryForObject("SELECT stock_mode FROM product WHERE id = ?", String.class, productId);
    }

    @Test
    @DisplayName("대기 시간이 지나고 PING 이 되면 키는 DB 값(stock − 미적용 합)이고 모드는 REDIS 다")
    void recoverOnce_대기시간뒤_키는_DB값이고_모드는_REDIS다() {
        dbModeSince("5 seconds");

        job.recoverOnce();

        assertThat(redisTemplate.opsForValue().get(key())).isEqualTo("7");
        assertThat(mode()).isEqualTo("REDIS");
    }

    @Test
    @DisplayName("예전 키가 더 큰 값으로 남아 있어도 복귀 뒤 키는 stock − 미적용 합으로 덮인다")
    void recoverOnce_오래된_키가_남아_있어도_DB값으로_덮는다() {
        dbModeSince("5 seconds");
        productStockRedisRepository.initStock(productId, 99);

        job.recoverOnce();

        assertThat(redisTemplate.opsForValue().get(key())).isEqualTo("7");
        assertThat(mode()).isEqualTo("REDIS");
    }

    @Test
    @DisplayName("대기 시간 전에는 아무것도 바뀌지 않는다")
    void recoverOnce_대기시간_전에는_변화가_없다() {
        dbModeSince("0 seconds");

        job.recoverOnce();

        assertThat(redisTemplate.hasKey(key())).isFalse();
        assertThat(mode()).isEqualTo("DB");
    }

    @Test
    @DisplayName("Redis PING 이 실패하면 아무것도 바뀌지 않는다")
    void recoverOnce_PING_실패면_변화가_없다() {
        dbModeSince("5 seconds");
        doReturn(false).when(productStockRedisRepository).ping();

        job.recoverOnce();

        assertThat(redisTemplate.hasKey(key())).isFalse();
        assertThat(mode()).isEqualTo("DB");
    }

    @Test
    @DisplayName("REDIS 모드 상품은 건드리지 않는다")
    void recoverOnce_REDIS모드_상품은_건드리지_않는다() {
        job.recoverOnce();

        assertThat(redisTemplate.hasKey(key())).isFalse();
        assertThat(mode()).isEqualTo("REDIS");
    }

    @Test
    @DisplayName("복귀 뒤 DB 모드 조건부 UPDATE 는 0 행에 영향을 준다")
    void recoverOnce_복귀뒤_DB모드_조건부UPDATE는_0행이다() {
        dbModeSince("5 seconds");

        job.recoverOnce();

        assertThat(stockLedgerRepository.decreaseInDbModeIfEnough(productId, 1)).isZero();
        assertThat(jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId)).isEqualTo(10);
    }
}
