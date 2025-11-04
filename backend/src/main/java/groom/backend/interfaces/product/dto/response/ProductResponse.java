package groom.backend.interfaces.product.dto.response;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.enums.ProductStatus;

import java.util.UUID;

public record ProductResponse(
        UUID productId,
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
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getStatus(),
                product.getCategory()
        );
    }
}
