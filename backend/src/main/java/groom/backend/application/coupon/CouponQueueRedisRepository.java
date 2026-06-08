package groom.backend.application.coupon;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 쿠폰 비동기 발급 대기열(ZSet) 관리.
 *
 * <p>하나의 쿠폰에 다수가 몰릴 때 본인의 "추정 대기 순번"을 보여주기 위한 용도다.
 * score 는 타임스탬프가 아니라 원자적 INCR 카운터를 써야(동일 ms 충돌 방지) 접수 순서가 정확하다.
 * 같은 사용자가 재요청해도 순번이 점프하지 않도록 ZADD NX(addIfAbsent)로 멱등 등록한다.
 * 실제 처리 순서는 Kafka 파티션이 결정하므로, 이 순번은 "보장"이 아니라 "추정 대기 순번"이다.
 *
 * <p>키: {@code coupon:seq:{couponId}} - 접수 순번 카운터, {@code coupon:queue:{couponId}} - 대기열 ZSet(member=userId)
 *
 * <p><b>키 정리 정책(7.3a):</b> 두 키 모두 접수마다 동일 TTL 로 갱신한다 →
 * 이벤트가 실제로 끝나(더 이상 접수가 없으면) TTL 이 만료되며 자동 정리된다(매진·중단 등 모든 종료 케이스 커버).
 * 활동 중에는 TTL 이 계속 갱신되므로 seq 카운터가 이벤트 도중 리셋돼 순번이 꼬일 위험은 없다.
 * 추가로 쿠폰 비활성화(명시적 이벤트 종료) 시 {@link #clear(Long)} 로 즉시 삭제해 2시간을 기다리지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class CouponQueueRedisRepository {

    private static final String SEQ_KEY_PREFIX = "coupon:seq:";
    private static final String QUEUE_KEY_PREFIX = "coupon:queue:";
    // seq 카운터와 큐 ZSet 모두에 적용하는 TTL(접수마다 갱신 → idle 시 자동 정리).
    private static final Duration KEY_TTL = Duration.ofHours(2);

    private final RedisTemplate<String, String> redisTemplate;

    // 접수: 원자적 순번 부여 후 ZSet 에 멱등 등록(이미 있으면 기존 순번 유지).
    public void enqueue(Long couponId, Long userId) {
        String seqKey = SEQ_KEY_PREFIX + couponId;
        String queueKey = QUEUE_KEY_PREFIX + couponId;
        Long seq = redisTemplate.opsForValue().increment(seqKey);
        redisTemplate.opsForZSet().addIfAbsent(queueKey, userId.toString(), seq != null ? seq : 0);
        // 두 키 모두 TTL 갱신 → 이벤트 종료(접수 중단) 후 함께 자동 만료(seq 키 영구 잔존 방지).
        redisTemplate.expire(seqKey, KEY_TTL);
        redisTemplate.expire(queueKey, KEY_TTL);
    }

    // 내 앞 대기 인원(0-based rank). 큐에 없으면(이미 처리됨/미등록) null.
    public Long rank(Long couponId, Long userId) {
        return redisTemplate.opsForZSet().rank(QUEUE_KEY_PREFIX + couponId, userId.toString());
    }

    // 현재 전체 대기 인원.
    public long size(Long couponId) {
        Long count = redisTemplate.opsForZSet().zCard(QUEUE_KEY_PREFIX + couponId);
        return count != null ? count : 0L;
    }

    // 처리 완료 시 대기열에서 제거 → 뒤 사용자들의 앞 대기 인원이 자연스럽게 감소.
    public void remove(Long couponId, Long userId) {
        redisTemplate.opsForZSet().remove(QUEUE_KEY_PREFIX + couponId, userId.toString());
    }

    // 이벤트 종료(쿠폰 비활성화 등) 시 대기열·순번 카운터를 즉시 삭제한다(idle TTL 을 기다리지 않는 명시적 정리).
    public void clear(Long couponId) {
        redisTemplate.delete(SEQ_KEY_PREFIX + couponId);
        redisTemplate.delete(QUEUE_KEY_PREFIX + couponId);
    }
}
