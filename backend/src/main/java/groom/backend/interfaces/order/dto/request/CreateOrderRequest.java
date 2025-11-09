package groom.backend.interfaces.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 생성 요청 DTO")
public record CreateOrderRequest(
        @Schema(description = "쿠폰 ID (선택사항)", example = "1")
        Long couponId
) {

}
