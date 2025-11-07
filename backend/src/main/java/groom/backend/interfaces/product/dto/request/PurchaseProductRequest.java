package groom.backend.interfaces.product.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseProductRequest {
    private UUID productId;
    private Integer quantity;
}


