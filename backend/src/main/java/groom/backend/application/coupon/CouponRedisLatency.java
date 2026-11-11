package groom.backend.application.coupon;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 접수 · 상태 조회 · 스트림 컨슈머의 Redis 호출이 부하 때 어디서 시간을 쓰는지 남긴다 (r120 쿠폰 500 rps 정체의 원인이 아직 없다 - 다음 측정이 읽는 자료)
 *
 * <p>단계마다 타이머 {@code coupon.stage}(태그 {@code stage}) 하나를 두고, 임계값({@code coupon.redis.slow-ms})을 넘긴 호출은
 * WARN 한 줄 {@code [COUPON_REDIS_SLOW]} 로 명령과 경과 시간을 남긴다. 줄은 백엔드당 초당 최대 1개이고 건너뛴 건수는 다음 줄의 suppressed 에 센다 -
 * 정체 중에 호출마다 줄을 쓰면 로그 쓰기가 요청 스레드를 더 붙든다
 */
@Slf4j
@Component
public class CouponRedisLatency {

    private final MeterRegistry registry;
    private final long slowMs;
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final AtomicLong lastLogMs = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();

    public CouponRedisLatency(MeterRegistry registry, @Value("${coupon.redis.slow-ms:200}") long slowMs) {
        this.registry = registry;
        this.slowMs = slowMs;
    }

    /** 호출을 단계 타이머에 기록한다. allowanceMs 는 호출이 일부러 기다리는 시간(XREADGROUP BLOCK)이라 느린 판정에서 뺀다. 예외도 기록한다 */
    public <T> T timed(String stage, String command, long allowanceMs, Supplier<T> call) {
        long started = System.nanoTime();
        try {
            return call.get();
        } finally {
            finish(stage, command, allowanceMs, System.nanoTime() - started);
        }
    }

    public void timed(String stage, String command, long allowanceMs, Runnable call) {
        timed(stage, command, allowanceMs, () -> {
            call.run();
            return null;
        });
    }

    /** 느린 판정 없이 단계 시간만 기록한다(요청 전체 시간) */
    public void record(String stage, long nanos) {
        timers.computeIfAbsent(stage, s -> Timer.builder("coupon.stage").tag("stage", s).register(registry))
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    // 시험이 앞 시험의 줄 간격을 이어받지 않게 한다
    void resetLogWindow() {
        lastLogMs.set(0);
        suppressed.set(0);
    }

    private void finish(String stage, String command, long allowanceMs, long nanos) {
        record(stage, nanos);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(nanos);
        if (elapsedMs > slowMs + allowanceMs) {
            logSlow(command, elapsedMs);
        }
    }

    private void logSlow(String command, long elapsedMs) {
        long now = System.currentTimeMillis();
        long last = lastLogMs.get();
        if (now - last >= 1000 && lastLogMs.compareAndSet(last, now)) {
            log.warn("[COUPON_REDIS_SLOW] command={} elapsedMs={} suppressed={}", command, elapsedMs, suppressed.getAndSet(0));
        } else {
            suppressed.incrementAndGet();
        }
    }
}
