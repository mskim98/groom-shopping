package groom.backend.interfaces.cart;

import groom.backend.application.cart.CartApplicationService;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.interfaces.cart.dto.request.RemoveCartItemsRequest;
import groom.backend.interfaces.cart.dto.response.CartItemResponse;
import groom.backend.interfaces.cart.dto.response.CartResponse;
import groom.backend.interfaces.product.dto.request.AddToCartRequest;
import groom.backend.interfaces.product.dto.response.AddToCartResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * 장바구니 관련 API를 제공하는 Controller입니다.
 */
@Slf4j
@RestController
@RequestMapping("/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartApplicationService cartApplicationService;

    /**
     * 장바구니에 제품을 추가합니다.
     */
    @PostMapping("/add")
    public ResponseEntity<AddToCartResponse> addToCart(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody AddToCartRequest request) {

        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (request == null || request.getProductId() == null || request.getQuantity() == null) {
            return ResponseEntity.badRequest().build();
        }

        Long userId = userDetails.getUser().getId();
        
        CartApplicationService.CartAddResult result = cartApplicationService.addToCart(
                userId,
                request.getProductId(),
                request.getQuantity()
        );

        AddToCartResponse response = AddToCartResponse.builder()
                .cartId(result.getCartId())
                .cartItemId(result.getCartItemId())
                .productId(request.getProductId())
                .quantity(result.getQuantity())
                .message("장바구니에 추가되었습니다.")
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 사용자의 장바구니에 담긴 모든 제품을 조회합니다.
     */
    @GetMapping
    public ResponseEntity<CartResponse> getCart(
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Long userId = userDetails.getUser().getId();
        
        CartApplicationService.CartViewResult result = cartApplicationService.getCartItems(userId);

        List<CartItemResponse> itemResponses = result.getItems().stream()
                .map(item -> CartItemResponse.builder()
                        .cartItemId(item.getCartItemId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .price(item.getPrice())
                        .quantity(item.getQuantity())
                        .totalPrice(item.getTotalPrice())
                        .createdAt(item.getCreatedAt())
                        .updatedAt(item.getUpdatedAt())
                        .build())
                .toList();

        CartResponse response = CartResponse.builder()
                .cartId(result.getCartId())
                .items(itemResponses)
                .totalItems(result.getTotalItems())
                .totalPrice(result.getTotalPrice())
                .message(result.getTotalItems() > 0 
                        ? String.format("%d개 제품이 장바구니에 담겨있습니다.", result.getTotalItems())
                        : "장바구니가 비어있습니다.")
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 장바구니에서 제품 수량을 줄이거나 제거합니다.
     * 하나 또는 여러 개의 제품을 한 번에 처리할 수 있습니다.
     * 수량을 지정하여 제거하며, 수량이 0이 되면 항목이 제거됩니다.
     * 하나라도 문제가 있으면 전체 실패 처리합니다.
     */
    @DeleteMapping("/remove")
    public ResponseEntity<?> removeCartItems(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody RemoveCartItemsRequest request) {

        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "제거할 제품 목록이 필요합니다."
            ));
        }

        Long userId = userDetails.getUser().getId();
        
        try {
            // CartItemToRemove 리스트로 변환
            List<CartApplicationService.CartItemToRemove> itemsToRemove = request.getItems().stream()
                    .map(item -> new CartApplicationService.CartItemToRemove(
                            item.getProductId(),
                            item.getQuantity()))
                    .toList();

            // 제거 처리 (하나라도 문제가 있으면 예외 발생)
            cartApplicationService.removeCartItems(userId, itemsToRemove);

            // 성공 시 간단한 메시지 반환
            return ResponseEntity.ok(java.util.Map.of(
                    "message", "장바구니에서 제품이 성공적으로 제거되었습니다."
            ));
        } catch (IllegalArgumentException e) {
            // 실패 시 에러 메시지만 반환
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", e.getMessage()
            ));
        }
    }

}

