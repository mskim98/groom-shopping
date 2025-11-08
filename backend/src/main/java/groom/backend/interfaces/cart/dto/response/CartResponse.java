package groom.backend.interfaces.cart.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 장바구니 조회 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "장바구니 조회 응답 DTO")
public class CartResponse {
    @Schema(description = "장바구니 ID", example = "1")
    private Long cartId;
    
    @Schema(description = "장바구니 항목 목록")
    private List<CartItemResponse> items;
    
    @Schema(description = "전체 항목 수", example = "3")
    private Integer totalItems;
    
    @Schema(description = "전체 금액 합계", example = "50000")
    private Integer totalPrice;
    
    @Schema(description = "응답 메시지", example = "3개 제품이 장바구니에 담겨있습니다.")
    private String message;
}


