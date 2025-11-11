package groom.backend.interfaces.cart;

import groom.backend.application.cart.CartApplicationService;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.interfaces.cart.dto.request.RemoveCartItemsRequest;
import groom.backend.interfaces.cart.dto.request.UpdateCartQuantityRequest;
import groom.backend.interfaces.cart.dto.response.CartItemResponse;
import groom.backend.interfaces.cart.dto.response.CartResponse;
import groom.backend.interfaces.cart.dto.response.UpdateCartQuantityResponse;
import groom.backend.interfaces.product.dto.request.AddToCartRequest;
import groom.backend.interfaces.product.dto.response.AddToCartResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Cart", description = "장바구니 관련 API")
@SecurityRequirement(name = "JWT")
public class CartController {

    private final CartApplicationService cartApplicationService;

    /**
     * 장바구니에 제품을 추가합니다.
     */
    @Operation(
            summary = "장바구니에 제품 추가",
            description = "장바구니에 제품을 추가합니다. 이미 존재하는 제품이면 수량이 증가합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "장바구니 추가 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AddToCartResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @PostMapping("/add")
    public ResponseEntity<AddToCartResponse> addToCart(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "장바구니 추가 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = AddToCartRequest.class))
            )
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
    @Operation(
            summary = "장바구니 조회",
            description = "현재 로그인한 사용자의 장바구니에 담긴 모든 제품을 조회합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "장바구니 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CartResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @GetMapping
    public ResponseEntity<CartResponse> getCart(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails) {

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
    @Operation(
            summary = "장바구니에서 제품 제거",
            description = "장바구니에서 제품을 제거하거나 수량을 줄입니다. 하나 또는 여러 제품을 한 번에 처리할 수 있으며, 하나라도 문제가 있으면 전체가 실패합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "제품 제거 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (제품이 장바구니에 없거나 수량 초과)"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @DeleteMapping("/remove")
    public ResponseEntity<?> removeCartItems(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "제거할 제품 목록",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RemoveCartItemsRequest.class))
            )
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

    /**
     * 장바구니 제품 수량을 증가시킵니다.
     * 재고량을 확인하여 재고량을 초과하지 않도록 합니다.
     */
    @Operation(
            summary = "장바구니 제품 수량 증가",
            description = "장바구니에 담긴 제품의 수량을 1개 증가시킵니다. 재고량을 확인하여 재고량을 초과하지 않도록 합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "수량 증가 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UpdateCartQuantityResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (재고 부족 등)"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @PatchMapping("/increase-quantity")
    public ResponseEntity<UpdateCartQuantityResponse> increaseQuantity(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수량 증가 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = UpdateCartQuantityRequest.class))
            )
            @RequestBody UpdateCartQuantityRequest request) {

        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (request == null || request.getProductId() == null) {
            return ResponseEntity.badRequest().build();
        }

        Long userId = userDetails.getUser().getId();

        try {
            CartApplicationService.CartQuantityUpdateResult result = 
                    cartApplicationService.increaseQuantity(userId, request.getProductId());

            UpdateCartQuantityResponse response = UpdateCartQuantityResponse.builder()
                    .productId(result.getProductId())
                    .quantity(result.getQuantity())
                    .stock(result.getStock())
                    .message("수량이 증가되었습니다.")
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(UpdateCartQuantityResponse.builder()
                    .productId(request.getProductId())
                    .message(e.getMessage())
                    .build());
        }
    }

    /**
     * 장바구니 제품 수량을 감소시킵니다.
     * 수량이 1이면 감소하지 않고, 2 이상이면 1개 감소시킵니다.
     */
    @Operation(
            summary = "장바구니 제품 수량 감소",
            description = "장바구니에 담긴 제품의 수량을 1개 감소시킵니다. 수량이 1이면 감소하지 않습니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "수량 감소 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = UpdateCartQuantityResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (수량이 1개 이하 등)"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @PatchMapping("/decrease-quantity")
    public ResponseEntity<UpdateCartQuantityResponse> decreaseQuantity(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수량 감소 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = UpdateCartQuantityRequest.class))
            )
            @RequestBody UpdateCartQuantityRequest request) {

        if (userDetails == null || userDetails.getUser() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (request == null || request.getProductId() == null) {
            return ResponseEntity.badRequest().build();
        }

        Long userId = userDetails.getUser().getId();

        try {
            CartApplicationService.CartQuantityUpdateResult result = 
                    cartApplicationService.decreaseQuantity(userId, request.getProductId());

            UpdateCartQuantityResponse response = UpdateCartQuantityResponse.builder()
                    .productId(result.getProductId())
                    .quantity(result.getQuantity())
                    .stock(result.getStock())
                    .message("수량이 감소되었습니다.")
                    .build();

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(UpdateCartQuantityResponse.builder()
                    .productId(request.getProductId())
                    .message(e.getMessage())
                    .build());
        }
    }

}

