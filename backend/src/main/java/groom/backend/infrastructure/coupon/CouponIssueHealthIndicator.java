package groom.backend.infrastructure.coupon;

import groom.backend.application.coupon.CouponStockRecoveryService;
import lombok.RequiredArgsConstructor;
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
@Component
@RequiredArgsConstructor
public class CouponIssueHealthIndicator implements HealthIndicator {

    private final CouponStockRecoveryService couponStockRecoveryService;

    @Override
    public Health health() {
        if (couponStockRecoveryService.isRecovering()) {
            return Health.outOfService().withDetail("reason", "쿠폰 재고 복구 중").build();
        }
        return Health.up().build();
    }
}
