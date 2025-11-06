package groom.backend.interfaces.payment.dto.request;

import groom.backend.domain.payment.model.enums.PaymentMethod;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PreparePaymentRequest {

    private UUID orderId;
    private PaymentMethod paymentMethod;
}
