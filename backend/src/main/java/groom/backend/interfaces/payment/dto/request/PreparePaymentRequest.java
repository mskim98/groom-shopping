package groom.backend.interfaces.payment.dto.request;

import groom.backend.domain.payment.model.enums.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "결제 준비 요청 DTO")
public class PreparePaymentRequest {

    @Schema(description = "주문 ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    private UUID orderId;
    
    @Schema(description = "결제 수단", example = "CARD", required = true)
    private PaymentMethod paymentMethod;
}
