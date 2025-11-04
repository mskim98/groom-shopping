package groom.backend.interfaces.cart;

import groom.backend.application.cart.CartApplicationService;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.interfaces.product.dto.request.AddToCartRequest;
import groom.backend.interfaces.product.dto.response.AddToCartResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


/**
 * 장바구니 관련 API를 제공하는 Controller입니다.
 */
@Slf4j
@RestController
@RequestMapping("/cart")
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

}

