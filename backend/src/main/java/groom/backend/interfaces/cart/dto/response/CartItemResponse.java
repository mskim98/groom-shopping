package groom.backend.interfaces.cart.dto.response;

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
public class CartItemResponse {
    private Long cartItemId;
    private UUID productId;
    private String productName;
    private Integer price;
    private Integer quantity;
    private Integer totalPrice;  // price * quantity
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}


