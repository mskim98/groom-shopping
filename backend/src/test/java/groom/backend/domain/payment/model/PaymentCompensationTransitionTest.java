package groom.backend.domain.payment.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("보상 레코드 상태 전이")
class PaymentCompensationTransitionTest {

    private PaymentCompensation newCompensation(int maxRetry) {
        return PaymentCompensation.builder()
                .paymentId(UUID.randomUUID())
                .paymentKey("pk-1")
                .amount(1000)
                .reason("DB 후처리 실패")
                .maxRetryCount(maxRetry)
                .build();
    }

    @Test
    @DisplayName("SUCCEEDED 는 종단이라 다시 실패로 되돌릴 수 없다")
    void succeededIsTerminal() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markSucceeded();

        assertThatThrownBy(() -> compensation.markFailed("뒤늦은 실패 보고"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_COMPENSATION_ILLEGAL_TRANSITION);
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("GIVEN_UP 은 종단이라 성공으로 덮어쓸 수 없다")
    void givenUpIsTerminal() {
        PaymentCompensation compensation = newCompensation(1);
        compensation.markFailed("1회 실패로 한도 소진");
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.GIVEN_UP);

        assertThatThrownBy(compensation::markSucceeded)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_COMPENSATION_ILLEGAL_TRANSITION);
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.GIVEN_UP);
    }

    @Test
    @DisplayName("PENDING·FAILED 에서의 정상 전이는 그대로 허용된다")
    void legalTransitionsStillWork() {
        PaymentCompensation retrying = newCompensation(5);
        assertThatCode(() -> retrying.markFailed("1회차 실패")).doesNotThrowAnyException();
        assertThat(retrying.getStatus()).isEqualTo(CompensationStatus.FAILED);
        assertThatCode(() -> retrying.markFailed("2회차 실패")).doesNotThrowAnyException();
        assertThatCode(retrying::markSucceeded).doesNotThrowAnyException();
        assertThat(retrying.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("백오프는 2·4·8·16분으로 커진다")
    void backoffDoublesFromTwoMinutes() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markFailed("1회차");
        assertThat(compensation.getNextRetryAt()).isAfter(java.time.LocalDateTime.now().plusMinutes(1));
        assertThat(compensation.getNextRetryAt()).isBefore(java.time.LocalDateTime.now().plusMinutes(3));
    }
}
