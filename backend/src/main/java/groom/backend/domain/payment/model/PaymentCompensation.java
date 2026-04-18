package groom.backend.domain.payment.model;

import groom.backend.domain.payment.model.enums.CompensationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Toss 승인 후 DB 처리(재고 차감 · 주문 상태 변경) 실패 시 Toss 취소 API로 자동 환불을 시도하고, 그 취소마저 실패한 경우 이 테이블에 기록한다.
 * {@code PaymentCompensationScheduler}가 주기적으로 {@code status = PENDING | FAILED} 이고 {@code nextRetryAt}이 도래한 건을 다시 시도
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
        this.reason = reason;
        this.status = CompensationStatus.PENDING;
        this.retryCount = 0;
        this.maxRetryCount = maxRetryCount != null ? maxRetryCount : 5;
        this.nextRetryAt = LocalDateTime.now();
    }

    public void markSucceeded() {
        this.status = CompensationStatus.SUCCEEDED;
        this.lastError = null;
        this.nextRetryAt = null;
    }

    /**
     * 보상 시도 실패 시 지수 백오프(1분, 2분, 4분, 8분, 16분…)로 다음 재시도 시각을 설정 재시도 한도를 넘기면 GIVEN_UP으로 상태를 바꾸고 수동 처리 대상으로 분류
     */
    public void markFailed(String errorMessage) {
        this.retryCount = this.retryCount + 1;
        this.lastError = errorMessage;

        if (this.retryCount >= this.maxRetryCount) {
            this.status = CompensationStatus.GIVEN_UP;
            this.nextRetryAt = null;
            return;
        }

        this.status = CompensationStatus.FAILED;
        long backoffMinutes = (long) Math.pow(2, Math.min(this.retryCount, 6));
        this.nextRetryAt = LocalDateTime.now().plusMinutes(backoffMinutes);
    }
}
