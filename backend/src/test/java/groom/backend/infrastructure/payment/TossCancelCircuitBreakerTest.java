package groom.backend.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 포트폴리오는 "서킷이 OPEN 인 구간에는 취소 API 호출도 fast-fail 된다" 고 서술한다.
 * 그 서술이 참이려면 cancelPayment 에 @CircuitBreaker 가 실제로 붙어 있어야 한다.
 */
@DisplayName("Toss 취소 API 서킷 보호")
class TossCancelCircuitBreakerTest {

    @Test
    @DisplayName("cancelPayment 는 승인과 분리된 toss-cancel 회로로 보호된다")
    void cancelPaymentIsProtectedBySeparateCircuit() throws NoSuchMethodException {
        Method method = TossPaymentClientImpl.class.getMethod(
                "cancelPayment", String.class, String.class, String.class);
        CircuitBreaker circuitBreaker = method.getAnnotation(CircuitBreaker.class);

        assertThat(circuitBreaker).as("cancelPayment 에 @CircuitBreaker 가 있어야 한다").isNotNull();
        assertThat(circuitBreaker.name())
                .as("취소 실패가 승인 회로를 열면 정상 결제까지 막힌다")
                .isEqualTo("toss-cancel");
        assertThat(circuitBreaker.fallbackMethod()).isEqualTo("cancelPaymentFallback");
    }
}
