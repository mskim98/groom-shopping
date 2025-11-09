package groom.backend.interfaces.payment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "결제 취소 요청 DTO")
public class CancelPaymentRequest {

    @Schema(description = "결제 ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    private UUID paymentId;
    
    @Schema(description = "취소 사유", example = "고객 변심", required = true)
    private String cancelReason;
}
