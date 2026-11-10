package groom.backend.application.coupon;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 접수 스크립트 호출 앞에 두는 {@code redis-coupon} 회로
 *
 * <p>Redis 가 끊기면 명령마다 연결 · 응답 제한 시간만큼 접수 스레드가 붙들린다.
 * 연속 실패가 임계를 넘으면 회로가 열려 이후 요청은 Redis 를 부르지 않고 바로 DB 경로로 간다.
 * 닫히는 것은 열린 지 {@code wait-open-ms} 뒤 시험 호출 몇 건이 성공했을 때이고, 그 사이 자동으로 Redis 로 돌아온다
 *
 * <p>yml 의 {@code resilience4j:} 블록이 아니라 코드에서 만든다. 임계값은 {@code coupon.issue.stream.circuit.*} 속성이다
 */
@Component
public class CouponRedisCircuit {

    public static final String NAME = "redis-coupon";

    private final CircuitBreaker breaker;

    public CouponRedisCircuit(CircuitBreakerRegistry registry,
                              @Value("${coupon.issue.stream.circuit.failure-rate-threshold:50}") float failureRateThreshold,
                              @Value("${coupon.issue.stream.circuit.minimum-calls:10}") int minimumCalls,
                              @Value("${coupon.issue.stream.circuit.window-size:20}") int windowSize,
                              @Value("${coupon.issue.stream.circuit.wait-open-ms:5000}") long waitOpenMs) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(minimumCalls)
                .slidingWindowSize(windowSize)
                .waitDurationInOpenState(Duration.ofMillis(waitOpenMs))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Redis 접근 오류만 실패로 센다. 스크립트 결과(품절 · 중복)는 정상 응답이다
                .recordExceptions(DataAccessException.class)
                .build();
        this.breaker = registry.circuitBreaker(NAME, config);
    }

    /**
     * Redis 호출을 회로 안에서 실행한다
     *
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException 회로가 열려 있을 때
     */
    public <T> T call(Supplier<T> redisCall) {
        return breaker.executeSupplier(redisCall);
    }

    public CircuitBreaker breaker() {
        return breaker;
    }
}
