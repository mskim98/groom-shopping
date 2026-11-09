package groom.backend.domain.payment.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
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

    @Test
    @DisplayName("deferWithoutCounting 은 PROCESSING 에서 PENDING 으로 돌아가고 retryCount 는 그대로 두며 nextRetryAt 만 미룬다")
    void deferWithoutCounting_PROCESSING에서_PENDING으로_돌아가고_nextRetryAt만_미룬다() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markProcessing();

        Integer retryCountBefore = compensation.getRetryCount();
        LocalDateTime deferredFrom = LocalDateTime.now();

        compensation.deferWithoutCounting(Duration.ofSeconds(45));

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(compensation.getRetryCount()).isEqualTo(retryCountBefore);
        assertThat(compensation.getNextRetryAt()).isAfterOrEqualTo(deferredFrom.plusSeconds(45));
        assertThat(compensation.getNextRetryAt()).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(45));
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

    // ── SUPERSEDED · NOT_APPROVED 종단 전이 ──

    @Test
    @DisplayName("PROCESSING 에서 SUPERSEDED 로 전이하면 nextRetryAt 을 비우고 사유를 남긴다")
    void processingToSupersededClearsRetryAndKeepsReason() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markProcessing();
        assertThatCode(() -> compensation.markSuperseded("r")).doesNotThrowAnyException();

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUPERSEDED);
        assertThat(compensation.getNextRetryAt()).isNull();
        assertThat(compensation.getLastError()).isEqualTo("r");
    }

    @Test
    @DisplayName("PROCESSING 에서 NOT_APPROVED 로 전이할 수 있다")
    void processingToNotApprovedIsAllowed() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markProcessing();
        assertThatCode(() -> compensation.markNotApproved("r")).doesNotThrowAnyException();

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.NOT_APPROVED);
    }

    @Test
    @DisplayName("SUPERSEDED 는 종단이라 성공으로 덮어쓸 수 없다")
    void supersededRejectsSucceeded() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markProcessing();
        compensation.markSuperseded("r");

        assertThatThrownBy(compensation::markSucceeded)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_COMPENSATION_ILLEGAL_TRANSITION);
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.SUPERSEDED);
    }

    @Test
    @DisplayName("NOT_APPROVED 는 종단이라 성공으로 덮어쓸 수 없다")
    void notApprovedRejectsSucceeded() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markProcessing();
        compensation.markNotApproved("r");

        assertThatThrownBy(compensation::markSucceeded)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_COMPENSATION_ILLEGAL_TRANSITION);
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.NOT_APPROVED);
    }

    // ── 종단 행 다시 열기 ──

    @Test
    @DisplayName("reopen 은 NOT_APPROVED 행을 새 값으로 PENDING 에 되돌리고 재시도 횟수·오류를 지운다")
    void reopenResetsTerminalRow() {
        PaymentCompensation compensation = newCompensation(5);
        compensation.markProcessing();
        compensation.markNotApproved("결제 없음");

        compensation.reopen("pk-new", 2000, "새 사유");

        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(compensation.getPaymentKey()).isEqualTo("pk-new");
        assertThat(compensation.getAmount()).isEqualTo(2000);
        assertThat(compensation.getReason()).isEqualTo("새 사유");
        assertThat(compensation.getRetryCount()).isZero();
        assertThat(compensation.getLastError()).isNull();
        assertThat(compensation.getNextRetryAt()).isNotNull();
        assertThat(compensation.isReopenable()).isFalse();
    }

    @Test
    @DisplayName("reopen 은 이미 재시도 대기(PENDING)인 행에는 쓸 수 없다")
    void reopenRejectsPendingRow() {
        PaymentCompensation compensation = newCompensation(5);

        assertThatThrownBy(() -> compensation.reopen("pk-new", 1000, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_COMPENSATION_ILLEGAL_TRANSITION);
    }

    @Test
    @DisplayName("reopen 은 SUCCEEDED · GIVEN_UP · RECONCILE_NEEDED 행에는 쓸 수 없다 - 사람 표식이나 끝난 환불을 지우지 않는다")
    void reopenRejectsNonReopenableTerminalRows() {
        PaymentCompensation succeeded = newCompensation(5);
        succeeded.markProcessing();
        succeeded.markSucceeded();
        PaymentCompensation givenUp = newCompensation(1);
        givenUp.markProcessing();
        givenUp.markFailed("x");
        PaymentCompensation reconcile = newCompensation(5);
        reconcile.markReconcileNeeded("대사 필요");

        for (PaymentCompensation row : List.of(succeeded, givenUp, reconcile)) {
            CompensationStatus before = row.getStatus();
            assertThatThrownBy(() -> row.reopen("pk-new", 1000, "새 사유"))
                    .as("%s 에서 reopen", before)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_COMPENSATION_ILLEGAL_TRANSITION);
            assertThat(row.getStatus()).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("reopenable 은 NOT_APPROVED 와 SUPERSEDED 뿐이다")
    void onlyNotApprovedAndSupersededAreReopenable() {
        PaymentCompensation notApproved = newCompensation(5);
        notApproved.markProcessing();
        notApproved.markNotApproved("r");
        PaymentCompensation superseded = newCompensation(5);
        superseded.markProcessing();
        superseded.markSuperseded("r");
        PaymentCompensation pending = newCompensation(5);

        assertThat(notApproved.isReopenable()).isTrue();
        assertThat(superseded.isReopenable()).isTrue();
        assertThat(pending.isReopenable()).isFalse();
    }
}
