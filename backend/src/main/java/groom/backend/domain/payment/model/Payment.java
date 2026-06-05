package groom.backend.domain.payment.model;

import groom.backend.domain.order.model.Order;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.domain.payment.model.enums.PaymentStateTransition;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.domain.payment.model.vo.Money;
import groom.backend.domain.payment.model.vo.PaymentKey;
import groom.backend.domain.payment.model.vo.TransactionId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
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

/**
 * 결제 엔티티. 주문 한 건에 대한 Toss Payments 결제를 표현한다.
 *
 * <p>상태 머신을 가진다:
 * {@code PENDING → READY → DONE / FAILED / CANCELED / PARTIAL_CANCELED / WAITING_FOR_DEPOSIT}.
 * 모든 상태 변경 메서드는 {@link PaymentStateTransition#validate}로
 * "이 상태에서 저 상태로 가도 되는지"를 먼저 검증한 뒤 값을 바꾼다.</p>
 *
 * <p>금액 필드들은 원시 타입(int) 대신 {@link Money} 값 객체로 감싸 통화/검증을 일관되게 처리한다.
 * {@code getXxxValue()} 편의 메서드로 내부 정수 값에 바로 접근할 수 있다.</p>
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor
public class Payment {

    // 결제 식별자 (UUID, JPA가 자동 생성)
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    // 이 결제가 속한 주문 (1:1). DB상 외래키이며 중복 불가(unique)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false, unique = true)
    private Order order;

    // 결제한 사용자 ID
    @Column(nullable = false)
    private Long userId;

    // Toss가 발급하는 결제 고유 키 (결제 승인/취소 API 호출 시 사용)
    @Embedded
    private PaymentKey paymentKey;

    // Toss 거래 식별자
    @Embedded
    private TransactionId transactionId;

    // 가장 최근 거래(취소 등)의 키
    @Column(name = "last_transaction_key", length = 200)
    private String lastTransactionKey;

    // 결제 요청 총 금액
    @Embedded
    private Money amount;

    // 취소 가능한 잔액 (부분 취소 시 줄어듦)
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "balance_amount"))
    })
    private Money balanceAmount;

    // 공급가액 (부가세 제외 금액)
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "supplied_amount"))
    })
    private Money suppliedAmount;

    // 부가가치세(VAT)
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "vat_amount"))
    })
    private Money vat;

    // 면세 금액
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "tax_free_amount"))
    })
    private Money taxFreeAmount;

    // 과세 제외 금액
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "tax_exemption_amount"))
    })
    private Money taxExemptionAmount;

    // 결제 상태 (상태 머신의 현재 위치) - 문자열로 DB에 저장
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    // 결제 수단 (카드, 가상계좌 등)
    @Enumerated(EnumType.STRING)
    @Column(name = "method")
    private PaymentMethod method;

    // 주문명 (Toss 결제창/영수증에 표시)
    @Column(name = "order_name")
    private String orderName;

    // 구매자 이름
    @Column(name = "customer_name")
    private String customerName;

    // Toss 가맹점 ID
    @Column(name = "m_id", length = 50)
    private String mId;

    // Toss 결제 객체 버전
    @Column(name = "version", length = 50)
    private String version;

    // Toss 결제 타입 (NORMAL, BILLING 등)
    @Column(name = "type", length = 50)
    private String type;

    // 통화 코드 (기본 KRW)
    @Column(name = "currency", length = 10)
    private String currency;

    // 에스크로 사용 여부
    @Column(name = "use_escrow")
    private Boolean useEscrow;

    // 문화비 소득공제 대상 여부
    @Column(name = "culture_expense")
    private Boolean cultureExpense;

    // 부분 취소 가능 여부
    @Column(name = "is_partial_cancelable")
    private Boolean isPartialCancelable;

    // 결제 요청 시각
    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    // 결제 승인 시각
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // 결제 취소 시각
    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    // 실패 코드 (실패 시 Toss가 내려주는 코드)
    @Column(name = "failure_code")
    private String failureCode;

    // 실패 사유 메시지
    @Column(name = "failure_message")
    private String failureMessage;

    // 결제수단 상세 정보 원본(JSON 등) 보관용
    @Column(name = "payment_method_details", columnDefinition = "TEXT")
    private String paymentMethodDetails;

    // 영수증 정보 원본 보관용
    @Column(name = "receipt", columnDefinition = "TEXT")
    private String receipt;

    // 결제창(checkout) 정보 원본 보관용
    @Column(name = "checkout", columnDefinition = "TEXT")
    private String checkout;

    // 결제 레코드 생성 시각 (최초 저장 시 자동 입력)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 마지막 수정 시각 (저장될 때마다 자동 갱신)
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 결제를 생성한다. 생성 직후 상태는 항상 {@link PaymentStatus#PENDING}이며,
     * 통화는 KRW, 에스크로/문화비/부분취소 플래그는 false로 초기화된다.
     */
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
        this.currency = "KRW";
        this.useEscrow = false;
        this.cultureExpense = false;
        this.isPartialCancelable = false;
    }

    // --- 편의 메서드: 값 객체(Money 등)를 거치지 않고 내부 값에 바로 접근 (null 안전) ---

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

    public Integer getBalanceAmountValue() {
        return this.balanceAmount != null ? this.balanceAmount.getValue() : null;
    }

    public Integer getSuppliedAmountValue() {
        return this.suppliedAmount != null ? this.suppliedAmount.getValue() : null;
    }

    public Integer getVatValue() {
        return this.vat != null ? this.vat.getValue() : null;
    }

    public Integer getTaxFreeAmountValue() {
        return this.taxFreeAmount != null ? this.taxFreeAmount.getValue() : null;
    }

    public Integer getTaxExemptionAmountValue() {
        return this.taxExemptionAmount != null ? this.taxExemptionAmount.getValue() : null;
    }

    // 비즈니스 로직: 결제 준비 상태로 변경
    public void ready(String paymentKey) {
        PaymentStateTransition.validate(this.status, PaymentStatus.READY);
        this.paymentKey = PaymentKey.of(paymentKey);
        this.status = PaymentStatus.READY;
        this.requestedAt = LocalDateTime.now();
    }

    // 비즈니스 로직: 결제 승인 (기본 정보)
    public void approve(String paymentKey, String transactionId) {
        PaymentStateTransition.validate(this.status, PaymentStatus.DONE);
        this.paymentKey = PaymentKey.of(paymentKey);
        this.transactionId = TransactionId.of(transactionId);
        this.status = PaymentStatus.DONE;
        this.approvedAt = LocalDateTime.now();
    }

    // 비즈니스 로직: 결제 승인 (Toss Payment API 응답으로부터)
    public void approveWithTossResponse(String paymentKey, String lastTransactionKey,
                                        Integer balanceAmount, Integer suppliedAmount,
                                        Integer vat, Integer taxFreeAmount,
                                        Integer taxExemptionAmount, String mId,
                                        String version, String type, String currency,
                                        Boolean useEscrow, Boolean cultureExpense,
                                        Boolean isPartialCancelable, LocalDateTime requestedAt,
                                        String paymentMethodDetails, String receipt,
                                        String checkout) {
        PaymentStateTransition.validate(this.status, PaymentStatus.DONE);
        this.paymentKey = PaymentKey.of(paymentKey);
        this.lastTransactionKey = lastTransactionKey;
        this.status = PaymentStatus.DONE;
        this.approvedAt = LocalDateTime.now();
        this.balanceAmount = Money.won(balanceAmount);
        this.suppliedAmount = Money.won(suppliedAmount);
        this.vat = Money.won(vat);
        this.taxFreeAmount = Money.won(taxFreeAmount);
        this.taxExemptionAmount = Money.won(taxExemptionAmount);
        this.mId = mId;
        this.version = version;
        this.type = type;
        this.currency = currency;
        this.useEscrow = useEscrow;
        this.cultureExpense = cultureExpense;
        this.isPartialCancelable = isPartialCancelable;
        this.requestedAt = requestedAt;
        this.paymentMethodDetails = paymentMethodDetails;
        this.receipt = receipt;
        this.checkout = checkout;
    }

    // 비즈니스 로직: 결제 취소
    public void cancel() {
        PaymentStateTransition.validate(this.status, PaymentStatus.CANCELED);
        this.status = PaymentStatus.CANCELED;
        this.canceledAt = LocalDateTime.now();
    }

    // 비즈니스 로직: 부분 취소
    public void partialCancel() {
        PaymentStateTransition.validate(this.status, PaymentStatus.PARTIAL_CANCELED);
        this.status = PaymentStatus.PARTIAL_CANCELED;
    }

    // 비즈니스 로직: 결제 실패 처리
    public void fail(String failureCode, String failureMessage) {
        PaymentStateTransition.validate(this.status, PaymentStatus.FAILED);
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    // 비즈니스 로직: 결제 만료 처리
    public void expire() {
        PaymentStateTransition.validate(this.status, PaymentStatus.EXPIRED);
        this.status = PaymentStatus.EXPIRED;
    }

    // 비즈니스 로직: 입금 대기 상태로 변경 (가상계좌)
    public void waitingForDeposit() {
        if (this.method != PaymentMethod.VIRTUAL_ACCOUNT) {
            throw new IllegalStateException("가상계좌 결제만 입금 대기 상태로 변경할 수 있습니다.");
        }
        PaymentStateTransition.validate(this.status, PaymentStatus.WAITING_FOR_DEPOSIT);
        this.status = PaymentStatus.WAITING_FOR_DEPOSIT;
    }

    // 비즈니스 로직: 결제 상태 변경 (State Machine 규칙 적용)
    public void changeStatus(PaymentStatus newStatus) {
        PaymentStateTransition.validate(this.status, newStatus);
        this.status = newStatus;
    }

    /**
     * 이미 승인된 결제인지 여부 (멱등성 체크용)
     */
    public boolean isAlreadyApproved() {
        return this.status == PaymentStatus.DONE
                || this.status == PaymentStatus.PARTIAL_CANCELED
                || this.status == PaymentStatus.CANCELED;
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
