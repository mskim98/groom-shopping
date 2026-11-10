package groom.backend.infrastructure.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 한 인스턴스가 연 서킷을 다른 인스턴스가 보게 하는 Redis 키 {@code cb:{name}:open_until}
 *
 * <p>인스턴스마다 회로가 따로 열리면 Toss 가 죽었을 때 3대가 각자 실패를 20건씩 겪은 뒤에야 닫힌다. OPEN 으로 바뀌는 순간
 * 열림이 끝나는 시각(epoch ms)을 같은 만료로 써 두고, 보호 호출 앞에서 읽어 자기 회로가 열린 것과 같은 예외로 끝낸다
 * <p>Redis 가 읽기 · 쓰기에서 예외를 던지면 자기 회로만 따른다(키 때문에 호출이 실패하는 일은 없다)
 * redis-stock · redis-coupon 은 대상이 Redis 자체라 여기에 올리지 않는다
 */
@Slf4j
@Component
public class SharedCircuitFlag {

    private final StringRedisTemplate redis;
    private final CircuitBreakerRegistry registry;
    private final List<String> names;
    // 장애 중 호출마다 로그가 쌓이지 않게 초당 1줄만 남긴다
    private final AtomicLong lastLogMs = new AtomicLong();

    public SharedCircuitFlag(StringRedisTemplate redis, CircuitBreakerRegistry registry,
                             @Value("${circuit.shared.names:toss,toss-cancel,db-ledger}") List<String> names) {
        this.redis = redis;
        this.registry = registry;
        this.names = names;
    }

    public static String key(String name) {
        return "cb:" + name + ":open_until";
    }

    @PostConstruct
    void subscribe() {
        names.forEach(name -> subscribe(registry.circuitBreaker(name)));
    }

    private void subscribe(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                long waitMs = breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
                publishOpen(breaker.getName(), waitMs);
            }
        });
    }

    /** OPEN 으로 바뀐 회로의 열림 종료 시각을 같은 만료로 쓴다. Redis 가 실패해도 던지지 않는다 */
    public void publishOpen(String name, long waitMs) {
        try {
            redis.opsForValue().set(key(name), String.valueOf(System.currentTimeMillis() + waitMs), Duration.ofMillis(waitMs));
        } catch (RuntimeException e) {
            logOnce("[CB_FLAG_WRITE_FAILED] name={} cause={}", name, e.toString());
        }
    }

    public boolean isOpen(String name) {
        try {
            String value = redis.opsForValue().get(key(name));
            return value != null && System.currentTimeMillis() < Long.parseLong(value);
        } catch (RuntimeException e) {
            logOnce("[CB_FLAG_READ_FAILED] name={} cause={}", name, e.toString());
            return false;
        }
    }

    /** 키가 살아 있으면 자기 회로가 열렸을 때와 같은 예외(CallNotPermittedException)로 끝낸다 */
    public void failFastIfOpen(String name) {
        if (isOpen(name)) {
            throw CallNotPermittedException.createCallNotPermittedException(registry.circuitBreaker(name));
        }
    }

    private void logOnce(String format, Object a, Object b) {
        long now = System.currentTimeMillis();
        long last = lastLogMs.get();
        if (now - last >= 1000 && lastLogMs.compareAndSet(last, now)) {
            log.warn(format, a, b);
        }
    }
}
