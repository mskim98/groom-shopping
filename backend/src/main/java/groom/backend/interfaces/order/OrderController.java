package groom.backend.interfaces.order;

import groom.backend.application.order.OrderApplicationService;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.order.model.Order;
import groom.backend.interfaces.order.dto.request.CreateOrderRequest;
import groom.backend.interfaces.order.dto.response.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
@Tag(name = "Order", description = "주문 관련 API")
@SecurityRequirement(name = "JWT")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    @Operation(
            summary = "주문 생성",
            description = "현재 사용자의 장바구니에 담긴 제품들을 주문합니다. 쿠폰을 적용할 수 있습니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "주문 생성 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<OrderResponse> createOrder(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "주문 생성 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = CreateOrderRequest.class))
            )
            @Valid @RequestBody CreateOrderRequest request
    ) {

        Long userId = user.getId();
        Long couponId = request.couponId();

        log.info("주문 생성 요청 - userId: {}, email: {}, couponId: {}",
                userId, user.getEmail(), request.couponId());

        // 서비스 호출 : 반환값(Order)
        Order order = orderApplicationService.createOrder(userId, couponId);

        // Response DTO 변환
        OrderResponse response = OrderResponse.from(order);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

//    @GetMapping
//    @ResponseStatus(HttpStatus.OK)
//    public List<OrderResponse> getOrders(@AuthenticationPrincipal User user) {
//
//    }
}
