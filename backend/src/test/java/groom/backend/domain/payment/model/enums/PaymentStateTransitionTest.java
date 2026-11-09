package groom.backend.domain.payment.model.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import groom.backend.domain.payment.model.Payment;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("결제 상태 전이 규칙(T3-9)")
class PaymentStateTransitionTest {

    // 자기 전이를 허용하는 유일한 상태 - 부분 취소는 여러 번 일어날 수 있다
    private static final Set<PaymentStatus> SELF_ALLOWED = EnumSet.of(PaymentStatus.PARTIAL_CANCELED);

    @Test
    @DisplayName("종료 상태에서는 같은 상태로의 자기 전이를 거부한다")
    void isAllowed_종료상태_자기전이_거부한다() {
        for (PaymentStatus status : PaymentStatus.values()) {
            if (SELF_ALLOWED.contains(status)) {
                continue;
            }
            assertThat(PaymentStateTransition.isAllowed(status, status))
                    .as("%s -> %s 자기 전이", status, status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("PARTIAL_CANCELED 상태에서는 자기 전이를 허용한다")
    void isAllowed_PARTIAL_CANCELED_자기전이_허용한다() {
        assertThat(PaymentStateTransition.isAllowed(
                PaymentStatus.PARTIAL_CANCELED, PaymentStatus.PARTIAL_CANCELED)).isTrue();
    }

    @Test
    @DisplayName("종료 상태에서 fail 호출 시 기존 failureCode를 덮어쓰지 않는다")
    void fail_종료상태에서_failureCode를_덮어쓰지_않는다() {
        Payment payment = Payment.builder().userId(1L).amount(1000).build();
        payment.fail("FIRST_CODE", "첫 실패 사유");

        assertThatThrownBy(() -> payment.fail("SECOND_CODE", "두 번째 사유"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(payment.getFailureCode()).isEqualTo("FIRST_CODE");
        assertThat(payment.getFailureMessage()).isEqualTo("첫 실패 사유");
    }
}
