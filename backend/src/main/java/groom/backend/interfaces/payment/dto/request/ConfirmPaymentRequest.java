package groom.backend.interfaces.payment.dto.request;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmPaymentRequest {

    private String paymentKey;
    private UUID orderId;
    private Integer amount;
}
