package groom.backend.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.product.ConditionalUpdateStockDecrementer;
import groom.backend.application.product.StockLedgerRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 상품 상세 수량의 Redis L2 - L2 적중 시 수량 SQL 0, 재고 · 재고 모드 쓰기마다 커밋 뒤 제거, 롤백이면 유지, 플래시 REDIS 모드는 살아 있는 Redis 값
@DisplayName("상품 상세 수량 L2 캐시")
class StockQuantityL2CacheTest extends ProductCacheTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUANTITY_SELECT = "select stock, stock_mode from product";

    @Autowired
    private ProductDetailCache cache;
    @Autowired
    private StockLedgerRepository ledger;
    @Autowired
    private ConditionalUpdateStockDecrementer decrementer;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private int fetchStock(UUID id) throws Exception {
        String json = mockMvc.perform(get("/v1/product/{id}", id)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        return JSON.readTree(json).get("stock").asInt();
    }

    private boolean l2Present(UUID id) {
        return Boolean.TRUE.equals(redis.hasKey(ProductDetailCache.quantityKey(id)));
    }

    private long quantitySelects() {
        return CacheTestSupport.countContaining(QUANTITY_SELECT);
    }

    // 쓰기를 한 트랜잭션에서 하고, 커밋 전에는 L2 항목이 남고 커밋 뒤에는 사라지며 다음 읽기가 새 값을 보이는지 본다
    private void assertEvictedAfterCommit(UUID id, int before, Runnable write, int expectedAfter) throws Exception {
        assertThat(fetchStock(id)).isEqualTo(before);
        assertThat(l2Present(id)).as("쓰기 전 L2 항목").isTrue();
        AtomicBoolean presentBeforeCommit = new AtomicBoolean();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            write.run();
            presentBeforeCommit.set(l2Present(id));
        });

        assertThat(presentBeforeCommit).as("커밋 전 L2 항목").isTrue();
        assertThat(l2Present(id)).as("커밋 뒤 L2 항목").isFalse();
        cache.invalidateQuantity(id);
        assertThat(fetchStock(id)).isEqualTo(expectedAfter);
    }

    private void toDbMode(UUID id) {
        jdbc.update("UPDATE product SET stock_mode = 'DB' WHERE id = ?", id);
    }

    @Test
    @DisplayName("L1 이 비고 L2 가 차 있으면 수량 SQL 이 0 이다")
    void 수량_L1만료_L2적중이면_수량SQL이_없다() throws Exception {
        UUID id = save("qty-a", 1500, 7, false);
        assertThat(fetchStock(id)).isEqualTo(7);
        assertThat(l2Present(id)).isTrue();
        cache.invalidateQuantity(id);
        CacheTestSupport.SQL.clear();

        assertThat(fetchStock(id)).isEqualTo(7);

        assertThat(quantitySelects()).as("수량 SQL").isZero();
    }

    @Test
    @DisplayName("REDIS 모드 플래시 상품은 L2 가 차 있어도 살아 있는 Redis 재고를 보이고 수량 SQL 은 0 이다")
    void 수량_REDIS모드_플래시는_L2가_있어도_Redis_재고를_보인다() throws Exception {
        UUID id = save("qty-b", 1500, 10, true);
        assertThat(fetchStock(id)).isEqualTo(10);
        stockRedisRepository.preReserve(id, 2);
        cache.invalidateQuantity(id);
        CacheTestSupport.SQL.clear();

        assertThat(fetchStock(id)).isEqualTo(8);

        assertThat(quantitySelects()).as("수량 SQL").isZero();
    }

    @Test
    @DisplayName("Redis 읽기가 실패하면 수량은 DB 에서 읽고 응답은 200 이다")
    void 수량_Redis_읽기_실패_DB값으로_200이다() throws Exception {
        UUID id = save("qty-c", 1500, 7, false);
        assertThat(fetchStock(id)).isEqualTo(7);
        cache.invalidateQuantity(id);
        CacheTestSupport.SQL.clear();
        doThrow(new RedisConnectionFailureException("down")).when(redis).opsForValue();

        assertThat(fetchStock(id)).isEqualTo(7);

        assertThat(quantitySelects()).as("수량 SQL").isEqualTo(1);
    }

    @Test
    @DisplayName("L2 항목의 만료는 프로퍼티 값을 따른다")
    void 수량_L2_만료는_프로퍼티를_따른다() throws Exception {
        UUID id = save("qty-d", 1500, 7, false);
        fetchStock(id);
        redis.delete(ProductDetailCache.quantityKey(id));
        ProductDetailCache other = new ProductDetailCache(redis, jdbc, 1000, 200, 3000);

        other.read(id, unused -> {
            throw new AssertionError("본문 L2 가 차 있으므로 DB 로더는 불리지 않는다");
        });

        Long ttlMs = redis.getExpire(ProductDetailCache.quantityKey(id), TimeUnit.MILLISECONDS);
        assertThat(ttlMs).isBetween(1L, 3000L);
    }

    @Test
    @DisplayName("일반 상품의 조건부 차감(decreaseStockIfEnough)은 커밋 뒤에 L2 항목을 지운다")
    void 쓰기_조건부차감_커밋뒤_L2를_지운다() throws Exception {
        UUID id = save("qty-w1", 1500, 7, false);

        assertEvictedAfterCommit(id, 7, () -> decrementer.decrease(id, 2), 5);
    }

    @Test
    @DisplayName("원장 적용(lowerStock)은 커밋 뒤에 L2 항목을 지운다")
    void 쓰기_lowerStock_커밋뒤_L2를_지운다() throws Exception {
        UUID id = save("qty-w2", 1500, 7, false);

        assertEvictedAfterCommit(id, 7, () -> ledger.lowerStock(id, 3), 4);
    }

    @Test
    @DisplayName("DB 모드 조건부 차감(decreaseInDbModeIfEnough)은 커밋 뒤에 L2 항목을 지운다")
    void 쓰기_DB모드_조건부차감_커밋뒤_L2를_지운다() throws Exception {
        UUID id = save("qty-w3", 1500, 9, true);
        toDbMode(id);

        assertEvictedAfterCommit(id, 9, () -> ledger.decreaseInDbModeIfEnough(id, 2), 7);
    }

    @Test
    @DisplayName("DB 모드 조건부 차감(decreaseInDbModeReturningStock)은 커밋 뒤에 L2 항목을 지운다")
    void 쓰기_DB모드_차감_재고반환_커밋뒤_L2를_지운다() throws Exception {
        UUID id = save("qty-w4", 1500, 9, true);
        toDbMode(id);

        assertEvictedAfterCommit(id, 9, () -> ledger.decreaseInDbModeReturningStock(id, 4), 5);
    }

    @Test
    @DisplayName("DB 모드 전환(setModeDb)은 커밋 뒤에 L2 항목을 지우고 다음 읽기가 DB 재고를 보인다")
    void 쓰기_setModeDb_커밋뒤_L2를_지운다() throws Exception {
        UUID id = save("qty-w5", 1500, 10, true);
        stockRedisRepository.preReserve(id, 4);

        assertEvictedAfterCommit(id, 6, () -> ledger.setModeDb(id), 10);
    }

    @Test
    @DisplayName("REDIS 모드 복귀(setModeRedis)는 커밋 뒤에 L2 항목을 지우고 다음 읽기가 Redis 재고를 보인다")
    void 쓰기_setModeRedis_커밋뒤_L2를_지운다() throws Exception {
        UUID id = save("qty-w6", 1500, 10, true);
        toDbMode(id);
        stockRedisRepository.preReserve(id, 4);

        assertEvictedAfterCommit(id, 10, () -> ledger.setModeRedis(id), 6);
    }

    @Test
    @DisplayName("엔티티 저장(ProductJpaEntity 의 stock 변경)은 커밋 뒤에 L2 항목을 지운다")
    void 쓰기_엔티티_저장_커밋뒤_L2를_지운다() throws Exception {
        UUID id = save("qty-w7", 1500, 7, false);

        assertEvictedAfterCommit(id, 7, () -> {
            ProductJpaEntity entity = productRepository.findById(id).orElseThrow();
            entity.setStock(3);
            productRepository.save(entity);
        }, 3);
    }

    @Test
    @DisplayName("롤백된 쓰기는 L2 항목을 지우지 않고 읽기는 옛 값이다")
    void 쓰기_롤백이면_L2를_유지한다() throws Exception {
        UUID id = save("qty-r", 1500, 7, false);
        assertThat(fetchStock(id)).isEqualTo(7);

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            ledger.lowerStock(id, 3);
            tx.setRollbackOnly();
        });

        assertThat(l2Present(id)).isTrue();
        cache.invalidateQuantity(id);
        assertThat(fetchStock(id)).isEqualTo(7);
    }

    @Test
    @DisplayName("커밋 전에 다른 연결이 L2 에 채운 옛 값도 커밋 뒤에 지워진다")
    void 쓰기_커밋전_읽기가_채운_옛값도_커밋뒤_지워진다() throws Exception {
        UUID id = save("qty-race", 1500, 7, false);
        assertThat(fetchStock(id)).isEqualTo(7);

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            ledger.lowerStock(id, 3);
            // 커밋 전의 제거를 흉내낸다 - 다른 연결의 읽기는 아직 옛 값 7 을 읽어 L2 에 다시 채운다
            redis.delete(ProductDetailCache.quantityKey(id));
            cache.invalidateQuantity(id);
            int seenByOther = CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchStock(id);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).join();
            assertThat(seenByOther).as("커밋 전 다른 연결의 읽기").isEqualTo(7);
            assertThat(l2Present(id)).as("옛 값이 L2 에 채워짐").isTrue();
        });

        assertThat(l2Present(id)).as("커밋 뒤 L2 항목").isFalse();
        cache.invalidateQuantity(id);
        assertThat(fetchStock(id)).isEqualTo(4);
    }
}
