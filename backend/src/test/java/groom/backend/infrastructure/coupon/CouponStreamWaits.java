package groom.backend.infrastructure.coupon;

import static org.awaitility.Awaitility.await;

import groom.backend.application.coupon.CouponStockRedisRepository;
import java.time.Duration;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 쿠폰 스트림 시험이 고정 시간 대신 Redis 가 알려 주는 상태를 기다리게 하는 도구 */
final class CouponStreamWaits {

    private CouponStreamWaits() {
    }

    /** 쿠폰 스트림의 대기 항목이 모두 minIdle 이상 쉴 때까지 기다린다 - 유휴 항목 인수가 가져갈 수 있는 상태다 */
    static void awaitPendingIdle(StringRedisTemplate redis, Long couponId, Duration minIdle) {
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            var pending = redis.opsForStream().pending(CouponStockRedisRepository.streamKey(couponId),
                    CouponStreamConsumer.GROUP, Range.unbounded(), 1000);
            return pending != null && !pending.isEmpty()
                    && pending.stream().map(PendingMessage::getElapsedTimeSinceLastDelivery)
                            .allMatch(idle -> idle.compareTo(minIdle) >= 0);
        });
    }
}
