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

    // ── T5: PROCESSING 전이 (다중 인스턴스 선점) ──

    @Test
    @DisplayName("PENDING 은 선점되어 PROCESSING 으로 전이할 수 있다")
    void pendingToProcessingIsAllowed() {
        PaymentCompensation compensation = newCompensation(5);
        assertThatCode(compensation::markProcessing).doesNotThrowAnyException();
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.PROCESSING);
    }

    @Test
    @DisplayName("FAILED 도 선점되어 PROCESSING 으로 전이할 수 있다")
    void failedToProcessingIsAllowed() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markFailed("1회차 실패");
        assertThatCode(compensation::markProcessing).doesNotThrowAnyException();
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.PROCESSING);
    }

    @Test
    @DisplayName("PROCESSING 에서 성공 기록으로 전이할 수 있다")
    void processingToSucceededIsAllowed() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markProcessing();
        assertThatCode(compensation::markSucceeded).doesNotThrowAnyException();
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("PROCESSING 에서 실패 기록으로 전이할 수 있다")
    void processingToFailedIsAllowed() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markProcessing();
        assertThatCode(() -> compensation.markFailed("취소 호출 실패")).doesNotThrowAnyException();
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.FAILED);
    }

    @Test
    @DisplayName("SUCCEEDED 는 종단이라 PROCESSING 으로 되돌릴 수 없다")
    void succeededRejectsProcessing() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markSucceeded();

        assertThatThrownBy(compensation::markProcessing)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_COMPENSATION_ILLEGAL_TRANSITION);
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
    }
}
