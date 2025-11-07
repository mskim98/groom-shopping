package groom.backend.infrastructure.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TossPaymentResponse {

    private String paymentKey;
    private String orderId;
    private String orderName;
    private String method;
    private Integer totalAmount;
    private String status;
    private String approvedAt;
    private String transactionKey;
}
