package groom.backend.interfaces.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "제품 구매 요청 DTO (선택사항: 없으면 장바구니 전체 구매)")
public class PurchaseProductRequest {
    @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID productId;
    
    @Schema(description = "구매 수량", example = "1", minimum = "1")
    private Integer quantity;
}



