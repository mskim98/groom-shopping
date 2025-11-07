package groom.backend.interfaces.payment.dto.response;

import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.domain.payment.model.enums.PaymentStatus;
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
public class PaymentResponse {

    private UUID id;
    private UUID orderId;
    private Long userId;
    private String paymentKey;
    private String transactionId;
    private Integer amount;
    private PaymentStatus status;
    private PaymentMethod method;
    private String orderName;
    private String customerName;
    private LocalDateTime approvedAt;
    private LocalDateTime canceledAt;
    private String failureCode;
    private String failureMessage;
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
