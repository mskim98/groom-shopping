package groom.backend.interfaces.product;

import groom.backend.application.product.ProductApplicationService;
import groom.backend.interfaces.product.dto.request.PurchaseProductRequest;
import groom.backend.interfaces.product.dto.response.PurchaseProductResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 제품 관련 API를 제공하는 Controller입니다.
 */
@Slf4j
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductApplicationService productApplicationService;

    /**
     * 제품을 구매합니다.
     * 재고 차감 후 임계값 도달 시 Kafka로 알림 이벤트를 발행합니다.
     */
    @PostMapping("/purchase")
    public ResponseEntity<PurchaseProductResponse> purchaseProduct(
            @RequestBody PurchaseProductRequest request) {
        
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
