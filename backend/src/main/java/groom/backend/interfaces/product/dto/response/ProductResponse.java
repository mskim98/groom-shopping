package groom.backend.interfaces.product.dto.response;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.enums.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "제품 응답 DTO")
public record ProductResponse(
        @Schema(description = "제품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID productId,
        @Schema(description = "제품명", example = "상품명")
        String name,
        @Schema(description = "제품 설명", example = "상품 설명입니다")
        String description,
        @Schema(description = "가격", example = "10000")
        Integer price,
        @Schema(description = "재고", example = "100")
        Integer stock,
        @Schema(description = "제품 상태", example = "AVAILABLE")
        ProductStatus status,
        @Schema(description = "카테고리", example = "ELECTRONICS")
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
