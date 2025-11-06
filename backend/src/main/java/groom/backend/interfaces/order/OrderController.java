package groom.backend.interfaces.order;

import groom.backend.application.order.OrderApplicationService;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.order.model.Order;
import groom.backend.interfaces.order.dto.request.CreateOrderRequest;
import groom.backend.interfaces.order.dto.response.OrderResponse;
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
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<OrderResponse> createOrder(
            @AuthenticationPrincipal(expression = "user") User user,
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
