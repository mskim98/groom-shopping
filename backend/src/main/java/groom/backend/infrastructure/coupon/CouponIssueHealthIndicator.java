package groom.backend.infrastructure.coupon;

import groom.backend.application.coupon.CouponStockRecoveryService;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 복구 중에는 트래픽을 받지 않는다
 *
 * <p>재고를 세우는 중에 요청을 받으면 판정 근거가 아직 없는 상태에서 통과 여부를 정하게 된다
 * 헬스체크를 실패로 돌려 로드밸런서 단계에서 걸러 내고, 그래도 도달한 요청은 접수가 503 으로 돌려보낸다
 * 두 겹인 이유는 로드밸런서가 상태를 반영하는 데 걸리는 시간 동안에도 요청이 들어오기 때문이다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueHealthIndicator implements HealthIndicator {

    private final CouponStockRecoveryService couponStockRecoveryService;

    // 재고 키 부재 검사 간격. 검사는 활성 쿠폰 수만큼 Redis 를 읽으므로 헬스 체크 호출마다 돌리지 않는다
    @Value("${coupon.recovery.health-check-interval-ms:30000}")
    private long checkIntervalMs = 30_000L;

    private final AtomicLong lastCheckAt = new AtomicLong(0L);

    @Override
    public Health health() {
        if (couponStockRecoveryService.isRecovering()) {
            return Health.outOfService().withDetail("reason", "쿠폰 재고 복구 중").build();
        }
        if (dueForStockCheck()) {
            // Redis 가 비워졌다면 여기서 복구가 돈다. 돌고 있는 동안의 호출은 위에서 OUT_OF_SERVICE 로 걸러진다
            // 헬스 체크가 예외로 실패하면 안 된다 - Redis 장애는 접수가 503 으로, Redis 헬스가 따로 알린다
            try {
                couponStockRecoveryService.recoverIfStockMissing();
            } catch (Exception e) {
                log.warn("[COUPON_HEALTH_RECOVERY_CHECK_FAILED] error={}", e.toString());
            }
        }
        return Health.up().build();
    }

    private boolean dueForStockCheck() {
        long now = System.currentTimeMillis();
        long previous = lastCheckAt.get();
        return now - previous >= checkIntervalMs && lastCheckAt.compareAndSet(previous, now);
    }
}
