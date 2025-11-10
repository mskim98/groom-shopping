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
@Schema(description = "장바구니 추가 응답 DTO")
public class AddToCartResponse {
    @Schema(description = "장바구니 ID", example = "1")
    private Long cartId;
    
    @Schema(description = "장바구니 항목 ID", example = "1")
    private Long cartItemId;
    
    @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID productId;
    
    @Schema(description = "수량", example = "1")
    private Integer quantity;
    
    @Schema(description = "응답 메시지", example = "장바구니에 추가되었습니다.")
    private String message;
}

