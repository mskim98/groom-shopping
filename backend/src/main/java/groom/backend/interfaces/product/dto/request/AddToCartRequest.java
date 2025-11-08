package groom.backend.interfaces.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "장바구니 추가 요청 DTO")
public class AddToCartRequest {
    @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
    private UUID productId;
    
    @Schema(description = "수량", example = "1", required = true, minimum = "1")
    private Integer quantity;
}

