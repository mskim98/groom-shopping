package groom.backend.interfaces.order.dto.response;

import groom.backend.domain.order.model.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "주문 항목 응답 DTO")
public record OrderItemResponse(
        @Schema(description = "주문 항목 ID", example = "1")
        Long orderItemId,
        @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID productId,
        @Schema(description = "제품명", example = "상품명")
        String productName,
        @Schema(description = "단가", example = "10000")
        Integer price,
        @Schema(description = "수량", example = "2")
        Integer quantity,
        @Schema(description = "소계", example = "20000")
        Integer subTotal
) {
    public static OrderItemResponse from(OrderItem orderItem) {
        return new OrderItemResponse(
                orderItem.getId(),
                orderItem.getProductId(),
                orderItem.getProductName(),
                orderItem.getPrice(),
                orderItem.getQuantity(),
                orderItem.getSubtotal()
        );
    }
}
