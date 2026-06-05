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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// @Slf4j : log 객체 생성.
@Slf4j
// @RestController : 응답을 JSON으로 반환하는 REST 컨트롤러.
@RestController
// @RequestMapping : 주문 API 공통 기본 경로.
@RequestMapping("/v1/order")
// @RequiredArgsConstructor : final 필드 생성자 주입.
@RequiredArgsConstructor
// @Tag : Swagger 문서 'Order' 그룹.
@Tag(name = "Order", description = "주문 관련 API")
// @SecurityRequirement : JWT 인증 필요 API임을 문서에 표시.
@SecurityRequirement(name = "JWT")
public class OrderController {

    // private final : 스프링이 생성자로 주입, 외부에서 교체 불가 → 안전한 의존성 사용.
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
    // 주문 생성 흐름: 요청 → 인증 사용자/요청값 검증 → 주문 서비스 호출 → 201 응답
    // @PostMapping : POST 요청 매핑.
    @PostMapping
    // @ResponseStatus(CREATED) : 정상 처리 시 HTTP 201(Created) 상태 코드로 응답한다.
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<OrderResponse> createOrder(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "주문 생성 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = CreateOrderRequest.class))
            )
            // @Valid : 요청 DTO의 검증 규칙(@NotNull 등)을 컨트롤러 진입 전 자동 검사한다.
            // @RequestBody : 요청 본문 JSON을 CreateOrderRequest 객체로 변환.
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

    @Operation(
            summary = "주문 상세 조회",
            description = "특정 주문의 상세 정보를 조회합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "주문을 찾을 수 없음")
    })
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @Parameter(hidden = true) @AuthenticationPrincipal(expression = "user") User user,
            @Parameter(description = "주문 ID", required = true)
            @PathVariable UUID orderId
    ) {
        Long userId = user.getId();

        log.info("주문 상세 조회 요청 - userId: {}, orderId: {}", userId, orderId);

        // 서비스 호출
        Order order = orderApplicationService.getOrderById(orderId, userId);

        // Response DTO 변환
        OrderResponse response = OrderResponse.from(order);

        return ResponseEntity.ok(response);
    }

}
