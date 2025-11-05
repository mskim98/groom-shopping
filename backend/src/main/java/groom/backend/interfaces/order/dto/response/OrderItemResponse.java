package groom.backend.interfaces.order.dto.response;

import groom.backend.domain.order.model.OrderItem;
import java.util.UUID;

public record OrderItemResponse(
        Long orderItemId,
        UUID productId,
        String productName,
        Integer price,
        Integer quantity,
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
