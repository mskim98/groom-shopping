package groom.backend.interfaces.product.dto.request;

import groom.backend.domain.product.model.enums.ProductCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "제품 수정 요청 DTO")
public record UpdateProductRequest(
        @Schema(description = "상품명", example = "수정된 상품명")
        @Size(min = 1, max = 100, message = "상품명은 1-100자 사이여야 합니다")
        String name,

        @Schema(description = "상품 설명", example = "수정된 상품 설명")
        @Size(max = 1000, message = "상품 설명은 1000자 이하여야 합니다")
        String description,

        @Schema(description = "가격", example = "15000")
        @Min(value = 0, message = "가격은 0 이상이어야 합니다")
        Integer price,

        @Schema(description = "재고", example = "50")
        @Min(value = 0, message = "재고는 0 이상이어야 합니다")
        Integer stock,

        @Schema(description = "카테고리", example = "ELECTRONICS")
        ProductCategory category
) {
}
