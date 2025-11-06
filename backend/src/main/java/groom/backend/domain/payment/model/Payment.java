package groom.backend.domain.payment.model;

import groom.backend.domain.order.model.Order;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.domain.payment.model.vo.Money;
import groom.backend.domain.payment.model.vo.PaymentKey;
import groom.backend.domain.payment.model.vo.TransactionId;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "orderId", nullable = false, unique = true)
    private Order order;

    @Column(name = "userId", nullable = false)
    private Long userId;

    @Embedded
    private PaymentKey paymentKey;

    @Embedded
    private TransactionId transactionId;

    @Embedded
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "method")
    private PaymentMethod method;

    @Column(name = "order_name")
    private String orderName;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Payment(Order order, Long userId, Integer amount, String orderName,
                   String customerName, PaymentMethod method) {
        this.order = order;
        this.userId = userId;
        this.amount = Money.won(amount);
        this.orderName = orderName;
        this.customerName = customerName;
        this.method = method;
        this.status = PaymentStatus.PENDING;
    }

    public UUID getOrderId() {
        return this.order != null ? this.order.getId() : null;
    }

    public Integer getAmountValue() {
        return this.amount != null ? this.amount.getValue() : null;
    }

    public String getPaymentKeyValue() {
        return this.paymentKey != null ? this.paymentKey.getValue() : null;
    }

    public String getTransactionIdValue() {
        return this.transactionId != null ? this.transactionId.getValue() : null;
    }

    // 비즈니스 로직: 결제 준비 상태로 변경
    public void ready(String paymentKey) {
        this.paymentKey = PaymentKey.of(paymentKey);
        this.status = PaymentStatus.READY;
    }

    // 비즈니스 로직: 결제 승인
    public void approve(String paymentKey, String transactionId) {
        this.paymentKey = PaymentKey.of(paymentKey);
        this.transactionId = TransactionId.of(transactionId);
        this.status = PaymentStatus.DONE;
        this.approvedAt = LocalDateTime.now();
    }

    // 비즈니스 로직: 결제 취소
    public void cancel() {
        if (this.status != PaymentStatus.DONE) {
            throw new IllegalStateException("완료된 결제만 취소할 수 있습니다.");
        }
        this.status = PaymentStatus.CANCELED;
        this.canceledAt = LocalDateTime.now();
    }

    // 비즈니스 로직: 부분 취소
    public void partialCancel() {
        if (this.status != PaymentStatus.DONE && this.status != PaymentStatus.PARTIAL_CANCELED) {
            throw new IllegalStateException("완료된 결제만 부분 취소할 수 있습니다.");
        }
        this.status = PaymentStatus.PARTIAL_CANCELED;
    }

    // 비즈니스 로직: 결제 실패 처리
    public void fail(String failureCode, String failureMessage) {
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    // 비즈니스 로직: 결제 만료 처리
    public void expire() {
        this.status = PaymentStatus.EXPIRED;
    }

    // 비즈니스 로직: 입금 대기 상태로 변경 (가상계좌)
    public void waitingForDeposit() {
        if (this.method != PaymentMethod.VIRTUAL_ACCOUNT) {
            throw new IllegalStateException("가상계좌 결제만 입금 대기 상태로 변경할 수 있습니다.");
        }
        this.status = PaymentStatus.WAITING_FOR_DEPOSIT;
    }

    // 비즈니스 로직: 결제 상태 변경
    public void changeStatus(PaymentStatus newStatus) {
        this.status = newStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Payment)) {
            return false;
        }
        Payment payment = (Payment) o;
        return Objects.equals(id, payment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
