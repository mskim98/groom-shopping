package groom.backend.interfaces.cart.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 장바구니에서 제품을 제거하기 위한 요청 DTO
 * 하나 또는 여러 개의 제품을 제거할 수 있습니다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemoveCartItemsRequest {
    
    /**
     * 제거할 제품 목록
     * 하나의 제품만 제거하거나 여러 제품을 한 번에 제거할 수 있습니다.
     */
    @NotEmpty(message = "제거할 제품 목록은 필수입니다")
    @Valid
    private List<CartItemToRemove> items;
    
    /**
     * 제거할 제품 정보를 담는 내부 클래스
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItemToRemove {
        @NotNull(message = "제품 ID는 필수입니다")
        private UUID productId;
        
        @NotNull(message = "제거할 수량은 필수입니다")
        @Min(value = 1, message = "제거할 수량은 1 이상이어야 합니다")
        private Integer quantity;
    }
}

