package groom.backend.interfaces.order.dto.response;

import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.enums.OrderStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


public record OrderResponse(
        UUID orderId,
        Long userId,
        Integer subTotal,
        Integer discountAmount,
        Integer totalAmount,
        OrderStatus status,
        Long couponId,
        LocalDateTime createdAt,
        List<OrderItemResponse> orderItems
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getSubTotal(),
                order.getDiscountAmount(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getCouponId(),
                order.getCreatedAt(),
                order.getOrderItems().stream()
                        .map(OrderItemResponse::from)
                        .toList()
        );
    }
}
