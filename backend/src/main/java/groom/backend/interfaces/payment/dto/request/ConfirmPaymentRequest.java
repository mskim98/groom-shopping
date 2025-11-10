package groom.backend.interfaces.payment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "결제 승인 요청 DTO")
public class ConfirmPaymentRequest {

    @Schema(description = "결제 키", example = "tgen_20240101_abc123", required = true)
    private String paymentKey;
    
    @Schema(description = "주문 ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    private UUID orderId;
    
    @Schema(description = "결제 금액", example = "100000", required = true)
    private Integer amount;
}
