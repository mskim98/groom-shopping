package groom.backend.application.product;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis 기반 상품 재고 선점.
 *
 * <p>결제 진입 시 DB(원천)에 손대기 전 Redis 에서 먼저 재고를 선점(DECRBY)해, 재고가 없으면 외부 PG 호출까지 가지 않고 즉시 차단한다.
 * "GET → 부족 검증 → DECRBY" 는 단일 Lua 로 원자화해야 동시 선점에서 음수로 내려가지 않는다.
 * Redis 는 빠른 게이트일 뿐 최종 정합성은 DB 낙관적 락(@Version)이 보장한다(2단 방어).
 *
 * <p>키: {@code product:stock:{productId}} - 남은 재고(INT)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ProductStockRedisRepository {

    public static final String STOCK_KEY_PREFIX = "product:stock:";

    // Lua 반환 코드: -2=미초기화(DB 폴백), -1=재고 부족, 0 이상=선점 성공 후 남은 수량
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

    private final RedisTemplate<String, String> redisTemplate;

    // 상품 재고를 Redis 에 초기화 (부팅 시 워밍업 / DB 폴백 후 채울 때 사용)
    public void initStock(UUID productId, int quantity) {
        redisTemplate.opsForValue().set(STOCK_KEY_PREFIX + productId, String.valueOf(quantity));
    }

    /**
     * 원자적 재고 선점.
     *
     * @return -2 미초기화(Redis 키 없음 → DB 폴백), -1 재고 부족, 0 이상 선점 성공(남은 수량)
     */
    public long preReserve(UUID productId, int quantity) {
        Long result = redisTemplate.execute(
                RESERVE_SCRIPT,
                List.of(STOCK_KEY_PREFIX + productId),
                String.valueOf(quantity));
        return result != null ? result : -2L;
    }

    // 선점 복원 (결제 실패·취소·보상 시 호출해 Redis-DB 정합 유지)
    public void release(UUID productId, int quantity) {
        redisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + productId, quantity);
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
