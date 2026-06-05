package groom.backend.application.coupon;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis 기반 쿠폰 재고 · 발급자 집합 관리
 *
 * <p>락 획득 이후에도 "수량 확인 → 중복 체크 → 수량 차감" 은 단일 원자 연산
 * Spring {@code RedisTemplate.execute(...)} 한 번에 여러 명령을 보내도 원자성은 보장되지 않으므로 Redis Lua 스크립트로 묶어 서버 사이드에서 한 번에 수행
 * <p>
 * 키: {@code coupon:stock:{couponId}} - 남은 재고 (INT), {@code coupon:issued_users:{couponId}} - 이미 발급받은 userId SET
 */
// @Slf4j : Lombok이 log 객체 생성.
@Slf4j
// @Repository : 데이터 저장소 접근 계층임을 표시한다(여기선 DB가 아닌 Redis 접근 담당).
@Repository
// @RequiredArgsConstructor : final 필드(redisTemplate) 생성자 주입.
@RequiredArgsConstructor
public class CouponStockRedisRepository {

    // Redis 키 규칙. 키에 couponId 를 붙여 쿠폰마다 재고/발급자 집합을 분리한다.
    public static final String STOCK_KEY_PREFIX = "coupon:stock:";
    public static final String ISSUED_USERS_KEY_PREFIX = "coupon:issued_users:";

    /**
     * Lua 반환 코드: 1=성공, 0=품절, -1=중복 발급. KEYS[1]=재고 키, KEYS[2]=발급자 SET 키 ARGV[1]=userId
     */
    private static final RedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>(
            """
                    local stock = tonumber(redis.call('GET', KEYS[1]))
                    if stock == nil then
                        return -2
                    end
                    if stock <= 0 then
                        return 0
                    end
                    if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
                        return -1
                    end
                    redis.call('DECR', KEYS[1])
                    redis.call('SADD', KEYS[2], ARGV[1])
                    return 1
                    """,
            Long.class
    );

    // RedisTemplate : 스프링이 제공하는 Redis 명령 실행 도구. private final 로 안전하게 주입받는다.
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 쿠폰 재고를 초기화 (관리자가 쿠폰을 발행할 때 1회 호출)
     */
    public void initStock(Long couponId, Long quantity) {
        redisTemplate.opsForValue().set(STOCK_KEY_PREFIX + couponId, String.valueOf(quantity));
    }

    public Long getStock(Long couponId) {
        String value = redisTemplate.opsForValue().get(STOCK_KEY_PREFIX + couponId);
        return value == null ? null : Long.parseLong(value);
    }

    /**
     * 원자적 발급 시도. 분산 락 구간 내에서 호출
     *
     * @return {@link IssueResult#SUCCESS} / {@link IssueResult#OUT_OF_STOCK} / {@link IssueResult#ALREADY_ISSUED} /
     * {@link IssueResult#NOT_INITIALIZED}
     */
    public IssueResult tryIssue(Long couponId, Long userId) {
        List<String> keys = List.of(
                STOCK_KEY_PREFIX + couponId,
                ISSUED_USERS_KEY_PREFIX + couponId
        );
        Long result = redisTemplate.execute(ISSUE_SCRIPT, keys, userId.toString());
        if (result == null) {
            return IssueResult.NOT_INITIALIZED;
        }
        return switch (result.intValue()) {
            case 1 -> IssueResult.SUCCESS;
            case 0 -> IssueResult.OUT_OF_STOCK;
            case -1 -> IssueResult.ALREADY_ISSUED;
            default -> IssueResult.NOT_INITIALIZED;
        };
    }

    /**
     * Lua 결과를 DB 커밋 실패 등으로 롤백해야 할 때 사용
     */
    public void rollbackIssue(Long couponId, Long userId) {
        redisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + couponId);
        redisTemplate.opsForSet().remove(ISSUED_USERS_KEY_PREFIX + couponId, userId.toString());
    }

    public enum IssueResult {
        SUCCESS,
        OUT_OF_STOCK,
        ALREADY_ISSUED,
        NOT_INITIALIZED
    }
}
