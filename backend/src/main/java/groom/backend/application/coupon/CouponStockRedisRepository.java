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
 * 키: {@code coupon:{couponId}:stock} - 남은 재고 (INT), {@code coupon:{couponId}:issued_users} - 이미 발급받은 userId SET
 */
// @Slf4j : Lombok이 log 객체 생성.
@Slf4j
// @Repository : 데이터 저장소 접근 계층임을 표시한다(여기선 DB가 아닌 Redis 접근 담당).
@Repository
// @RequiredArgsConstructor : final 필드(redisTemplate) 생성자 주입.
@RequiredArgsConstructor
public class CouponStockRedisRepository {

    // 한 쿠폰의 재고 키와 발급자 SET 키를 같은 해시 슬롯에 묶는다
    // Lua 가 두 키를 함께 다루므로, Cluster 에서 슬롯이 갈리면 CROSSSLOT 으로 항상 실패한다
    // 중괄호 안(couponId)만 슬롯 계산에 쓰이므로 두 키가 반드시 같은 노드에 놓인다
    private static final String KEY_PREFIX = "coupon:{";

    public static String stockKey(Long couponId) {
        return KEY_PREFIX + couponId + "}:stock";
    }

    public static String issuedUsersKey(Long couponId) {
        return KEY_PREFIX + couponId + "}:issued_users";
    }

    /**
     * Lua 반환 코드: 1=성공, 0=품절, -1=중복 발급, -2=미초기화. KEYS[1]=재고 키, KEYS[2]=발급자 SET 키 ARGV[1]=userId
     *
     * <p>중복 검사를 재고 검사보다 앞에 둔다. 뒤에 두면 재고가 0 이 된 뒤로는
     * 이미 받은 사용자의 재요청까지 "품절"로 응답돼, 실패 집계에서 중복과 품절이 섞인다
     * 재고 소진은 이벤트 초반에 오므로 그 뒤의 요청이 다수다 - 오분류가 예외가 아니라 기본값이 된다
     */
    private static final RedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>(
            """
                    if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
                        return -1
                    end
                    local stock = tonumber(redis.call('GET', KEYS[1]))
                    if stock == nil then
                        return -2
                    end
                    if stock <= 0 then
                        return 0
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
        redisTemplate.opsForValue().set(stockKey(couponId), String.valueOf(quantity));
    }

    /**
     * 키가 아직 없을 때만 재고를 심는다(SETNX). 이미 값이 있으면 건드리지 않고 {@code false} 를 반환
     *
     * <p>워밍업 전용이다. 이벤트 진행 중에는 Redis 재고가 살아 있는 카운터이고 DB 수량은
     * 커밋이 끝난 발급까지만 반영하므로, 재기동 시 {@link #initStock}(SET)으로 덮으면
     * Lua 차감은 끝났으나 DB 커밋 전인 요청 수만큼 재고가 되살아난다.
     */
    public boolean initStockIfAbsent(Long couponId, Long quantity) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue()
                        .setIfAbsent(stockKey(couponId), String.valueOf(quantity)));
    }

    public Long getStock(Long couponId) {
        String value = redisTemplate.opsForValue().get(stockKey(couponId));
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
                stockKey(couponId),
                issuedUsersKey(couponId)
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
        redisTemplate.opsForValue().increment(stockKey(couponId));
        redisTemplate.opsForSet().remove(issuedUsersKey(couponId), userId.toString());
    }

    /**
     * 재고만 되돌린다. 발급자 SET 은 건드리지 않는다
     *
     * <p>DB 유니크 제약에 막힌 경우에 쓴다. 이미 쿠폰을 가진 사용자를 SET 에서 빼면
     * 게이트가 그를 다시 통과시켜 같은 실패를 무한 반복한다
     */
    public void rollbackStockOnly(Long couponId) {
        redisTemplate.opsForValue().increment(stockKey(couponId));
    }

    public enum IssueResult {
        SUCCESS,
        OUT_OF_STOCK,
        ALREADY_ISSUED,
        NOT_INITIALIZED
    }
}
