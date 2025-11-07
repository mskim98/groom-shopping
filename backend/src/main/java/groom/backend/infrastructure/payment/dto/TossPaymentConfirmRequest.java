package groom.backend.infrastructure.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TossPaymentConfirmRequest {

    private String paymentKey;
    private String orderId;
    private Integer amount;
}
