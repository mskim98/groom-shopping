package groom.backend.interfaces.order.dto.response;

import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


@Schema(description = "주문 응답 DTO")
public record OrderResponse(
        @Schema(description = "주문 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID orderId,
        @Schema(description = "사용자 ID", example = "1")
        Long userId,
        @Schema(description = "소계", example = "100000")
        Integer subTotal,
        @Schema(description = "할인 금액", example = "10000")
        Integer discountAmount,
        @Schema(description = "총 금액", example = "90000")
        Integer totalAmount,
        @Schema(description = "주문 상태", example = "PENDING")
        OrderStatus status,
        @Schema(description = "쿠폰 ID", example = "1")
        Long couponId,
        @Schema(description = "생성 일시", example = "2024-01-01T12:00:00")
        LocalDateTime createdAt,
        @Schema(description = "주문 항목 목록")
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
