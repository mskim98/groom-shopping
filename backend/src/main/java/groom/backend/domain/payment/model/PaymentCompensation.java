package groom.backend.domain.payment.model;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Toss 승인 후 DB 처리(재고 차감 · 주문 상태 변경) 실패 시 Toss 취소 API로 자동 환불을 시도하고, 그 취소마저 실패한 경우 이 테이블에 기록한다.
 * {@code PaymentCompensationScheduler}가 주기적으로 {@code status = PENDING | FAILED} 이고 {@code nextRetryAt}이 지난 건을 다시 시도
 */
@Entity
@Table(name = "payment_compensation")
@Getter
@NoArgsConstructor
public class PaymentCompensation {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "payment_key", nullable = false, length = 200)
    private String paymentKey;

    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CompensationStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "max_retry_count", nullable = false)
    private Integer maxRetryCount;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public PaymentCompensation(UUID paymentId, String paymentKey, Integer amount, String reason,
                               Integer maxRetryCount) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.paymentKey = paymentKey;
        this.amount = amount;
        // reason 컬럼 500자 - 실패 원인 문구는 길이를 보장할 수 없어 잘라 저장한다
        this.reason = TextLimit.clip(reason, 500);
        this.status = CompensationStatus.PENDING;
        this.retryCount = 0;
        this.maxRetryCount = maxRetryCount != null ? maxRetryCount : 5;
        this.nextRetryAt = LocalDateTime.now();
    }

    // 허용 전이표. SUCCEEDED·GIVEN_UP 은 종단이라 어떤 전이도 나가지 않는다
    // 조회 쿼리(findRetryable)의 상태 조건에만 의존하면 새 호출부가 생기는 순간 뚫린다
    // PROCESSING 은 배치 인스턴스가 선점 조회로 붙이는 상태다. 선점 자체는 네이티브
    // UPDATE...RETURNING 으로 커밋되어 이 전이표를 거치지 않지만, 이 표는 이후 결과
    // 기록(markSucceeded·markFailed)이 PROCESSING 에서 나가는 것을 허용하는 데 쓰인다
    private static final Map<CompensationStatus, Set<CompensationStatus>> ALLOWED_TRANSITIONS = Map.of(
            CompensationStatus.PENDING, EnumSet.of(
                    CompensationStatus.PROCESSING, CompensationStatus.SUCCEEDED,
                    CompensationStatus.FAILED, CompensationStatus.GIVEN_UP),
            CompensationStatus.FAILED, EnumSet.of(
                    CompensationStatus.PROCESSING, CompensationStatus.SUCCEEDED,
                    CompensationStatus.FAILED, CompensationStatus.GIVEN_UP),
            CompensationStatus.PROCESSING, EnumSet.of(
                    CompensationStatus.SUCCEEDED, CompensationStatus.FAILED, CompensationStatus.GIVEN_UP),
            CompensationStatus.SUCCEEDED, EnumSet.noneOf(CompensationStatus.class),
            CompensationStatus.GIVEN_UP, EnumSet.noneOf(CompensationStatus.class));

    private void transitionTo(CompensationStatus next) {
        Set<CompensationStatus> allowed = ALLOWED_TRANSITIONS
                .getOrDefault(this.status, EnumSet.noneOf(CompensationStatus.class));
        if (!allowed.contains(next)) {
            throw new BusinessException(ErrorCode.PAYMENT_COMPENSATION_ILLEGAL_TRANSITION);
        }
        this.status = next;
    }

    /** 배치 인스턴스가 이 레코드를 선점했음을 엔티티 상태에도 반영한다 */
    public void markProcessing() {
        transitionTo(CompensationStatus.PROCESSING);
    }

    public void markSucceeded() {
        transitionTo(CompensationStatus.SUCCEEDED);
        this.lastError = null;
        this.nextRetryAt = null;
    }

    /**
     * 보상 시도 실패 시 지수 백오프(2분, 4분, 8분, 16분…)로 다음 재시도 시각을 설정 재시도 한도를 넘기면 GIVEN_UP으로 상태를 바꾸고 수동 처리 대상으로 분류
     */
    public void markFailed(String errorMessage) {
        // 상태를 먼저 바꾼다. 전이가 거부되면 retryCount 도 오르지 않아야 한다
        int nextRetryCount = this.retryCount + 1;
        if (nextRetryCount >= this.maxRetryCount) {
            transitionTo(CompensationStatus.GIVEN_UP);
            this.retryCount = nextRetryCount;
            this.lastError = errorMessage;
            this.nextRetryAt = null;
            return;
        }
        transitionTo(CompensationStatus.FAILED);
        this.retryCount = nextRetryCount;
        this.lastError = errorMessage;
        long backoffMinutes = (long) Math.pow(2, Math.min(this.retryCount, 6));
        this.nextRetryAt = LocalDateTime.now().plusMinutes(backoffMinutes);
    }

    /**
     * 토스가 살아 있다는 신호(429·403·409 응답 분류)로 재시도 대기가 필요할 때 호출한다
     * 상태·retryCount 는 그대로 두고 nextRetryAt 만 미룬다 - 실패로 세지 않기 위해 전이표를 거치지 않는다
     */
    public void deferWithoutCounting(Duration deferBy) {
        this.nextRetryAt = LocalDateTime.now().plus(deferBy);
    }
}
