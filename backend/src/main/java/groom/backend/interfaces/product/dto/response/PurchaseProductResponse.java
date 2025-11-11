package groom.backend.interfaces.product.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "제품 구매 응답 DTO")
public class PurchaseProductResponse {
    @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID productId;
    
    @Schema(description = "구매 수량", example = "1")
    private Integer quantity;
    
    @Schema(description = "남은 재고", example = "10")
    private Integer remainingStock;
    
    @Schema(description = "재고 임계값 도달 여부", example = "false")
    private Boolean stockThresholdReached;
    
    @Schema(description = "응답 메시지", example = "구매가 완료되었습니다.")
    private String message;
}



