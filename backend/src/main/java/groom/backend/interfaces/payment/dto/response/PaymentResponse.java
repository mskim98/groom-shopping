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

    @Schema(description = "결제 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;
    
    @Schema(description = "주문 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID orderId;
    
    @Schema(description = "사용자 ID", example = "1")
    private Long userId;
    
    @Schema(description = "결제 키", example = "tgen_20240101_abc123")
    private String paymentKey;
    
    @Schema(description = "거래 ID", example = "tx_1234567890")
    private String transactionId;
    
    @Schema(description = "결제 금액", example = "100000")
    private Integer amount;
    
    @Schema(description = "결제 상태", example = "APPROVED")
    private PaymentStatus status;
    
    @Schema(description = "결제 수단", example = "CARD")
    private PaymentMethod method;
    
    @Schema(description = "주문명", example = "주문명")
    private String orderName;
    
    @Schema(description = "고객명", example = "홍길동")
    private String customerName;
    
    @Schema(description = "승인 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime approvedAt;
    
    @Schema(description = "취소 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime canceledAt;
    
    @Schema(description = "실패 코드", example = "FAILED")
    private String failureCode;
    
    @Schema(description = "실패 메시지", example = "결제 실패")
    private String failureMessage;
    
    @Schema(description = "생성 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime createdAt;

    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .paymentKey(payment.getPaymentKeyValue())
                .transactionId(payment.getTransactionIdValue())
                .amount(payment.getAmountValue())
                .status(payment.getStatus())
                .method(payment.getMethod())
                .orderName(payment.getOrderName())
                .customerName(payment.getCustomerName())
                .approvedAt(payment.getApprovedAt())
                .canceledAt(payment.getCanceledAt())
                .failureCode(payment.getFailureCode())
                .failureMessage(payment.getFailureMessage())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
