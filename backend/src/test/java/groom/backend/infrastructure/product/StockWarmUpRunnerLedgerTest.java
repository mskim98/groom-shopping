package groom.backend.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 워밍업이 플래시 상품만 stock − 미적용 합으로 SET NX 하고, 있는 키 · DB 모드 상품 · 일반 상품은 건드리지 않는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("재고 워밍업의 원장 반영")
class StockWarmUpRunnerLedgerTest {

    @Autowired
    private StockWarmUpRunner runner;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private ProductStockRedisRepository stockRedisRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID flashId;
    private UUID presentKeyId;
    private UUID dbModeId;
    private UUID plainId;

    @BeforeEach
    void setUp() {
        flashId = product(10, true);
        presentKeyId = product(10, true);
        dbModeId = product(10, true);
        plainId = product(10, false);
        jdbc.update("UPDATE product SET stock_mode = 'DB', stock_mode_changed_at = now() WHERE id = ?", dbModeId);
        for (UUID id : new UUID[] {flashId, presentKeyId, dbModeId}) {
            jdbc.update("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) VALUES (?, ?, 3, 'SALE', false)",
                    UUID.randomUUID(), id);
        }
    }

    @AfterEach
    void tearDown() {
        for (UUID id : new UUID[] {flashId, presentKeyId, dbModeId, plainId}) {
            redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + id);
            jdbc.update("DELETE FROM stock_ledger WHERE product_id = ?", id);
            productRepository.deleteById(id);
        }
    }

    private UUID product(int stock, boolean flash) {
        return productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name("warmup").price(1000)
                .stock(stock).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(flash).build()).getId();
    }

    private String key(UUID id) {
        return redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + id);
    }

    @Test
    @DisplayName("플래시 상품은 stock − 미적용 합으로 적재한다 - 재고 10 · 미적용 SALE 3 이면 키 7")
    void run_플래시상품_재고에서_미적용합을_뺀_값으로_적재한다() {
        runner.run(new DefaultApplicationArguments());

        assertThat(key(flashId)).isEqualTo("7");
    }

    @Test
    @DisplayName("이미 있는 키는 건드리지 않는다")
    void run_있는_키는_건드리지_않는다() {
        stockRedisRepository.initStock(presentKeyId, 99);

        runner.run(new DefaultApplicationArguments());

        assertThat(key(presentKeyId)).isEqualTo("99");
    }

    @Test
    @DisplayName("DB 모드 플래시 상품과 일반 상품은 적재하지 않는다")
    void run_DB모드_플래시상품과_일반상품은_적재하지_않는다() {
        runner.run(new DefaultApplicationArguments());

        assertThat(key(dbModeId)).isNull();
        assertThat(key(plainId)).isNull();
    }
}
