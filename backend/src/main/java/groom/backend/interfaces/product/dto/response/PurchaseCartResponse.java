package groom.backend.interfaces.product.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 장바구니 전체 구매 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseCartResponse {
    
    /**
     * 개별 제품 구매 결과
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PurchaseItemResult {
        private UUID productId;
        private Integer quantity;
        private Integer remainingStock;
        private Boolean stockThresholdReached;
        private String message;
    }

    private List<PurchaseItemResult> results;
    private Integer totalItems;
    private String message;
}

