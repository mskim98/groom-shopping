package groom.backend.interfaces.product.dto.response;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.enums.ProductStatus;

public record ProductResponse(
        Long productId,
        String name,
        String description,
        Integer price,
        Integer stock,
        ProductStatus status,
        ProductCategory category
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName().getValue(),
                product.getDescription() != null ? product.getDescription().getValue() : null,
                product.getPrice().getAmount(),
                product.getStock().getAmount(),
                product.getStatus(),
                product.getCategory()
        );
    }
}
