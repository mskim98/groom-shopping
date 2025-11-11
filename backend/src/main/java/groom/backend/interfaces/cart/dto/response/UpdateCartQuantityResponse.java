package groom.backend.interfaces.cart.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 장바구니 수량 변경 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "장바구니 수량 변경 응답 DTO")
public class UpdateCartQuantityResponse {
    @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID productId;
    
    @Schema(description = "변경된 수량", example = "3")
    private Integer quantity;
    
    @Schema(description = "제품 재고량", example = "50")
    private Integer stock;
    
    @Schema(description = "응답 메시지", example = "수량이 증가되었습니다.")
    private String message;
}

