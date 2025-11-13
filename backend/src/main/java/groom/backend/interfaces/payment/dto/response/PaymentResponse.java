package groom.backend.interfaces.payment.dto.response;

import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "결제 응답 DTO")
public class PaymentResponse {

    // 기본 정보
    @Schema(description = "결제 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "주문 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID orderId;

    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @Schema(description = "PG사 ID", example = "tosspayments")
    private String mId;

    @Schema(description = "API 버전", example = "2022-11-16")
    private String version;

    // 결제 키 및 거래 정보
    @Schema(description = "결제 키", example = "tgen_20240101_abc123")
    private String paymentKey;

    @Schema(description = "마지막 거래 키", example = "tx_last_1234567890")
    private String lastTransactionKey;

    @Schema(description = "거래 ID", example = "tx_1234567890")
    private String transactionId;

    // 금액 정보
    @Schema(description = "결제 금액", example = "100000")
    private Integer amount;

    @Schema(description = "잔액", example = "100000")
    private Integer balanceAmount;

    @Schema(description = "공급가액", example = "90909")
    private Integer suppliedAmount;

    @Schema(description = "부가세", example = "9091")
    private Integer vat;

    @Schema(description = "세금 면제 금액", example = "0")
    private Integer taxFreeAmount;

    @Schema(description = "세금 감면액", example = "0")
    private Integer taxExemptionAmount;

    // 결제 상태
    @Schema(description = "결제 상태", example = "DONE")
    private PaymentStatus status;

    @Schema(description = "결제 수단", example = "CARD")
    private PaymentMethod method;

    @Schema(description = "결제 타입 (NORMAL, BILLING 등)", example = "NORMAL")
    private String type;

    @Schema(description = "통화 코드", example = "KRW")
    private String currency;

    @Schema(description = "에스크로 사용 여부", example = "false")
    private Boolean useEscrow;

    @Schema(description = "문화비 지출 여부", example = "false")
    private Boolean cultureExpense;

    @Schema(description = "부분 취소 가능 여부", example = "true")
    private Boolean isPartialCancelable;

    // 주문 정보
    @Schema(description = "주문명", example = "주문명")
    private String orderName;

    @Schema(description = "고객명", example = "홍길동")
    private String customerName;

    // 시간 정보
    @Schema(description = "요청 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime requestedAt;

    @Schema(description = "승인 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime approvedAt;

    @Schema(description = "취소 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime canceledAt;

    // 실패 정보
    @Schema(description = "실패 코드", example = "FAILED")
    private String failureCode;

    @Schema(description = "실패 메시지", example = "결제 실패")
    private String failureMessage;

    // 추가 정보
    @Schema(description = "결제 수단 상세정보 (JSON)", example = "{}")
    private String paymentMethodDetails;

    @Schema(description = "영수증 정보 (JSON)", example = "{}")
    private String receipt;

    @Schema(description = "체크아웃 URL (JSON)", example = "{}")
    private String checkout;

    @Schema(description = "생성 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime createdAt;

    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
                // 기본 정보
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .mId(payment.getMId())
                .version(payment.getVersion())
                // 결제 키 및 거래 정보
                .paymentKey(payment.getPaymentKeyValue())
                .lastTransactionKey(payment.getLastTransactionKey())
                .transactionId(payment.getTransactionIdValue())
                // 금액 정보
                .amount(payment.getAmountValue())
                .balanceAmount(payment.getBalanceAmountValue())
                .suppliedAmount(payment.getSuppliedAmountValue())
                .vat(payment.getVatValue())
                .taxFreeAmount(payment.getTaxFreeAmountValue())
                .taxExemptionAmount(payment.getTaxExemptionAmountValue())
                // 결제 상태
                .status(payment.getStatus())
                .method(payment.getMethod())
                .type(payment.getType())
                .currency(payment.getCurrency())
                .useEscrow(payment.getUseEscrow())
                .cultureExpense(payment.getCultureExpense())
                .isPartialCancelable(payment.getIsPartialCancelable())
                // 주문 정보
                .orderName(payment.getOrderName())
                .customerName(payment.getCustomerName())
                // 시간 정보
                .requestedAt(payment.getRequestedAt())
                .approvedAt(payment.getApprovedAt())
                .canceledAt(payment.getCanceledAt())
                // 실패 정보
                .failureCode(payment.getFailureCode())
                .failureMessage(payment.getFailureMessage())
                // 추가 정보
                .paymentMethodDetails(payment.getPaymentMethodDetails())
                .receipt(payment.getReceipt())
                .checkout(payment.getCheckout())
                // 생성 일시
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
