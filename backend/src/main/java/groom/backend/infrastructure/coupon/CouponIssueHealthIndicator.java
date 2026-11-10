package groom.backend.infrastructure.coupon;

import groom.backend.application.coupon.CouponStockRecoveryService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // 복구는 활성 쿠폰 전체의 Redis 읽기와 DB 발급 수 COUNT 를 포함한다. 프로브 스레드에서 돌리면 헬스 요청이 그만큼 걸려
    // 로드밸런서의 제한 시간을 넘기므로 전용 스레드 하나로 넘기고, 돌고 있는 동안은 다시 예약하지 않는다
    private final AtomicBoolean recoveryScheduled = new AtomicBoolean(false);
    private final ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coupon-stock-recovery");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Health health() {
        // 읽기만 한다. 복구 중이거나 재고 키가 없다고 판정된 동안은 접수가 503 으로 돌려보내므로 트래픽을 받지 않는다
        if (couponStockRecoveryService.isRecovering() || couponStockRecoveryService.isStockMissing()) {
            scheduleRecoveryIfDue();
            return Health.outOfService().withDetail("reason", "쿠폰 재고 복구 중 또는 재고 키 부재").build();
        }
        scheduleRecoveryIfDue();
        return Health.up().build();
    }

    // Redis 가 비워졌다면 전용 스레드에서 복구가 돈다
    // 복구가 예외로 끝나도 다음 검사가 다시 예약한다 - Redis 장애는 접수가 503 으로, Redis 헬스가 따로 알린다
    private void scheduleRecoveryIfDue() {
        if (!dueForStockCheck() || !recoveryScheduled.compareAndSet(false, true)) {
            return;
        }
        recoveryExecutor.execute(() -> {
            try {
                couponStockRecoveryService.recoverIfStockMissing();
            } catch (Exception e) {
                log.warn("[COUPON_HEALTH_RECOVERY_CHECK_FAILED] error={}", e.toString());
            } finally {
                recoveryScheduled.set(false);
            }
        });
    }

    @PreDestroy
    void shutdownRecoveryExecutor() {
        recoveryExecutor.shutdownNow();
    }

    private boolean dueForStockCheck() {
        long now = System.currentTimeMillis();
        long previous = lastCheckAt.get();
        return now - previous >= checkIntervalMs && lastCheckAt.compareAndSet(previous, now);
    }
}
