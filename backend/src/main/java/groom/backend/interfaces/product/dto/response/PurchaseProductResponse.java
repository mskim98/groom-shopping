package groom.backend.interfaces.product.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseProductResponse {
    private UUID productId;
    private Integer quantity;
    private Integer remainingStock;
    private Boolean stockThresholdReached;
    private String message;
}

