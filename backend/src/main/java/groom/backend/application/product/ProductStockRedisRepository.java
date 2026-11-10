package groom.backend.application.product;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 플래시 세일 상품의 Redis 재고 선점.
 *
 * <p>결제 진입 시 외부 PG 호출 전에 Redis 에서 먼저 재고를 선점(DECRBY)해, 재고가 없으면 PG 까지 가지 않고 즉시 차단한다.
 * "GET → 부족 검증 → DECRBY" 는 단일 Lua 로 원자화해야 동시 선점에서 음수로 내려가지 않는다.
 * stock_mode 가 REDIS 인 동안 판매 여부를 정하는 곳은 이 선점뿐이다. DB 에는 승인 트랜잭션이 판매를 원장(stock_ledger)에 INSERT 로 남기고
 * 1초 배치가 product.stock 에 접어 넣는다. 그래서 키의 값은 "product.stock − 미적용 원장 합" 과 같아야 한다(대조: StockReconciliationJob).
 * Redis 가 실패하면 상품이 DB 모드로 넘어가고, 복귀(StockModeRecoveryJob)가 키를 위 값으로 덮어쓴다.
 *
 * <p>키: {@code product:stock:{productId}} - 남은 재고(INT)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ProductStockRedisRepository {

    public static final String STOCK_KEY_PREFIX = "product:stock:";

    // Lua 반환 코드: -2=키 없음(DB 모드 전환), -1=재고 부족, 0 이상=선점 성공 후 남은 수량
    private static final RedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>(
            """
                    local stock = tonumber(redis.call('GET', KEYS[1]))
                    if stock == nil then
                        return -2
                    end
                    if stock < tonumber(ARGV[1]) then
                        return -1
                    end
                    return redis.call('DECRBY', KEYS[1], ARGV[1])
                    """,
            Long.class
    );

    // 복원은 키가 있을 때만 더한다. INCRBY 는 없는 키를 만들어 "1개 남음" 으로 읽히게 하므로 유실을 가린다
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            """
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        return -2
                    end
                    return redis.call('INCRBY', KEYS[1], ARGV[1])
                    """,
            Long.class
    );

    private final RedisTemplate<String, String> redisTemplate;

    // 상품 재고를 Redis 에 덮어쓴다 - 복귀(StockModeRecoveryJob)가 stock − 미적용 합으로 쓴다
    public void initStock(UUID productId, int quantity) {
        redisTemplate.opsForValue().set(STOCK_KEY_PREFIX + productId, String.valueOf(quantity));
    }

    // 키가 없을 때만 쓴다(SET NX) - 부팅 워밍업이 다른 인스턴스가 이미 쓴 키를 되돌리지 않게 한다
    public boolean initStockIfAbsent(UUID productId, int quantity) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(STOCK_KEY_PREFIX + productId, String.valueOf(quantity)));
    }

    // 복귀 전에 Redis 가 응답하는지 본다. 예외는 응답 없음으로 취급한다
    public boolean ping() {
        try {
            return "PONG".equalsIgnoreCase(redisTemplate.execute((RedisCallback<String>) connection -> connection.ping()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 원자적 재고 선점.
     *
     * @return -2 키 없음(DB 모드로 전환), -1 재고 부족, 0 이상 선점 성공(남은 수량)
     */
    public long preReserve(UUID productId, int quantity) {
        Long result = redisTemplate.execute(
                RESERVE_SCRIPT,
                List.of(STOCK_KEY_PREFIX + productId),
                String.valueOf(quantity));
        return result != null ? result : -2L;
    }

    // 선점 복원 (결제 실패·취소·보상 시 호출). 키가 없으면 만들지 않고 그대로 둔다
    public void release(UUID productId, int quantity) {
        redisTemplate.execute(RELEASE_SCRIPT, List.of(STOCK_KEY_PREFIX + productId), String.valueOf(quantity));
    }

    // 여러 상품의 재고를 한 번에 읽는다 (대조 배치용). 키가 없으면 해당 위치가 null
    public List<Integer> getStocks(List<UUID> productIds) {
        List<String> keys = productIds.stream().map(id -> STOCK_KEY_PREFIX + id).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return productIds.stream().map(id -> (Integer) null).toList();
        }
        return values.stream()
                .map(v -> v == null ? null : Integer.valueOf(v))
                .toList();
    }
}
