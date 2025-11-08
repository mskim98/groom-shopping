package groom.backend.interfaces.cart.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 장바구니 항목 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "장바구니 항목 응답 DTO")
public class CartItemResponse {
    @Schema(description = "장바구니 항목 ID", example = "1")
    private Long cartItemId;
    
    @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID productId;
    
    @Schema(description = "제품명", example = "상품명")
    private String productName;
    
    @Schema(description = "단가", example = "10000")
    private Integer price;
    
    @Schema(description = "수량", example = "2")
    private Integer quantity;
    
    @Schema(description = "총 가격 (단가 × 수량)", example = "20000")
    private Integer totalPrice;
    
    @Schema(description = "생성 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime createdAt;
    
    @Schema(description = "수정 일시", example = "2024-01-01T12:00:00")
    private LocalDateTime updatedAt;
}


