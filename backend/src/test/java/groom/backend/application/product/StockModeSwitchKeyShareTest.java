package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

// 장바구니 · 주문 insert 의 외래 키 검사가 product 행에 잡는 FOR KEY SHARE 를 전환이 기다리지 않는지, 재고 UPDATE 와의 직렬화는 남는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "stock.mode.lock-timeout-ms=5000"
})
@DisplayName("재고 모드 전환의 상품 행 잠금")
class StockModeSwitchKeyShareTest {

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
    private long userId;
    private long cartId;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        productId = productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name("key-share")
                .price(1000).stock(10).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(true).build()).getId();
        userId = jdbc.queryForObject("INSERT INTO users (email, password, name) VALUES (?, 'x', 'ks') RETURNING id", Long.class,
                UUID.randomUUID().toString().substring(0, 30) + "@ks.test");
        cartId = jdbc.queryForObject("INSERT INTO cart (user_id) VALUES (?) RETURNING id", Long.class, userId);
        // 커넥션 풀을 데워 두어 500ms 한도가 첫 연결 비용을 재지 않게 한다
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> jdbc.queryForObject("SELECT 1", Integer.class));
        }
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        pool.shutdownNow();
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        jdbc.update("DELETE FROM stock_ledger WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM cart_item WHERE cart_id = ?", cartId);
        jdbc.update("DELETE FROM cart WHERE id = ?", cartId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        productRepository.deleteById(productId);
    }

    private String mode() {
        return jdbc.queryForObject("SELECT stock_mode FROM product WHERE id = ?", String.class, productId);
    }

    // 장바구니에 이 상품을 넣고 커밋하지 않은 트랜잭션 - 외래 키 검사가 product 행에 FOR KEY SHARE 를 잡은 채 열려 있다
    private void holdKeyShareViaCartInsert() throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        pool.submit(() -> transactionTemplate.execute(status -> {
            jdbc.update("INSERT INTO cart_item (cart_id, product_id, quantity) VALUES (?, ?, 1)", cartId, productId);
            locked.countDown();
            awaitRelease();
            return null;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
    }

    // 조건부 재고 UPDATE 를 한 뒤 커밋하지 않은 트랜잭션 - 상품 행의 쓰기 잠금을 쥔다
    private void holdStockUpdate() throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        pool.submit(() -> transactionTemplate.execute(status -> {
            stockLedgerRepository.lowerStock(productId, 0);
            locked.countDown();
            awaitRelease();
            return null;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private void awaitRelease() {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("장바구니 insert 가 열려 있어도 전환은 500ms 안에 끝나 DB 모드가 된다")
    void switchToDb_장바구니insert가_열려있어도_500ms안에_끝나_DB모드가_된다() throws Exception {
        holdKeyShareViaCartInsert();

        Future<?> switching = pool.submit(() -> stockLedgerService.switchToDb(productId));
        switching.get(500, TimeUnit.MILLISECONDS);

        assertThat(mode()).isEqualTo("DB");
    }

    @Test
    @DisplayName("장바구니 insert 가 열려 있어도 적용 배치는 500ms 안에 그 상품의 미적용 행을 접어 넣는다")
    void applyProduct_장바구니insert가_열려있어도_500ms안에_미적용_행을_접어_넣는다() throws Exception {
        jdbc.update("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) VALUES (?, ?, 3, 'SALE', false)",
                UUID.randomUUID(), productId);
        holdKeyShareViaCartInsert();

        Future<Integer> applying = pool.submit(() -> stockLedgerService.applyProduct(productId));

        assertThat(applying.get(500, TimeUnit.MILLISECONDS)).isEqualTo(1);
    }

    @Test
    @DisplayName("조건부 재고 UPDATE 가 열려 있으면 전환은 그 트랜잭션이 끝날 때까지 기다린 뒤 끝난다")
    void switchToDb_재고UPDATE가_열려있으면_끝날때까지_기다린_뒤_끝난다() throws Exception {
        holdStockUpdate();

        Future<?> switching = pool.submit(() -> stockLedgerService.switchToDb(productId));
        assertThatThrownBy(() -> switching.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        assertThat(mode()).isEqualTo("REDIS");

        release.countDown();
        switching.get(5, TimeUnit.SECONDS);
        assertThat(mode()).isEqualTo("DB");
    }
}
