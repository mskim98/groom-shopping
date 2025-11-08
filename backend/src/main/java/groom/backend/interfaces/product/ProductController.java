package groom.backend.interfaces.product;

import groom.backend.application.product.ProductApplicationService;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.interfaces.product.dto.request.PurchaseProductRequest;
import groom.backend.interfaces.product.dto.response.PurchaseCartResponse;
import groom.backend.interfaces.product.dto.response.PurchaseProductResponse;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 제품 관련 API를 제공하는 Controller입니다.
 */
@Slf4j
@RestController
@RequestMapping("/v1/products")
@RequiredArgsConstructor
@Tag(name = "Product", description = "제품 구매 관련 API")
@SecurityRequirement(name = "JWT")
public class ProductController {

    private final ProductApplicationService productApplicationService;

    /**
     * 제품을 구매합니다.
     * body가 없으면 현재 사용자의 장바구니에 담긴 모든 제품을 구매합니다.
     * body가 있으면 해당 제품을 구매합니다.
     * 재고 차감 후 임계값 도달 시 Kafka로 알림 이벤트를 발행합니다.
     */
    @Operation(
            summary = "제품 구매",
            description = """
                    제품을 구매합니다.
                    - body가 없으면: 현재 사용자의 장바구니에 담긴 모든 제품을 구매합니다.
                    - body가 있으면: 해당 제품을 구매합니다.
                    재고 차감 후 임계값 도달 시 Kafka로 알림 이벤트를 발행합니다.
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "구매 성공",
                    content = {
                            @Content(mediaType = "application/json",
                                    schema = @Schema(oneOf = {PurchaseProductResponse.class, PurchaseCartResponse.class}))
                    }),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (재고 부족 등)"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @PostMapping("/purchase")
    public ResponseEntity<?> purchaseProduct(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "구매 요청 (선택사항: 없으면 장바구니 전체 구매)",
                    required = false,
                    content = @Content(schema = @Schema(implementation = PurchaseProductRequest.class))
            )
            @RequestBody(required = false) PurchaseProductRequest request) {
        
        // body가 없으면 장바구니 전체 구매
        if (request == null || request.getProductId() == null) {
            if (userDetails == null || userDetails.getUser() == null) {
                return ResponseEntity.status(401).body("인증이 필요합니다.");
            }
            
            Long userId = userDetails.getUser().getId();
            List<ProductApplicationService.PurchaseResult> results = 
                    productApplicationService.purchaseCartItems(userId);

            List<PurchaseCartResponse.PurchaseItemResult> responseResults = results.stream()
                    .map(result -> PurchaseCartResponse.PurchaseItemResult.builder()
                            .productId(result.getProductId())
                            .quantity(result.getQuantity())
                            .remainingStock(result.getRemainingStock())
                            .stockThresholdReached(result.getStockThresholdReached())
                            .message(result.getStockThresholdReached()
                                    ? String.format("재고가 %d개로 얼마 남지 않았어요", result.getRemainingStock())
                                    : "구매가 완료되었습니다.")
                            .build())
                    .toList();

            PurchaseCartResponse response = PurchaseCartResponse.builder()
                    .results(responseResults)
                    .totalItems(results.size())
                    .message(String.format("%d개 제품 구매가 완료되었습니다.", results.size()))
                    .build();

            return ResponseEntity.ok(response);
        }

        // body가 있으면 단일 제품 구매 (기존 로직)
        ProductApplicationService.PurchaseResult result = 
                productApplicationService.purchaseProduct(request.getProductId(), request.getQuantity());

        PurchaseProductResponse response = PurchaseProductResponse.builder()
                .productId(result.getProductId())
                .quantity(result.getQuantity())
                .remainingStock(result.getRemainingStock())
                .stockThresholdReached(result.getStockThresholdReached())
                .message(result.getStockThresholdReached() 
                        ? String.format("재고가 %d개로 얼마 남지 않았어요", result.getRemainingStock())
                        : "구매가 완료되었습니다.")
                .build();

        return ResponseEntity.ok(response);
    }
}
