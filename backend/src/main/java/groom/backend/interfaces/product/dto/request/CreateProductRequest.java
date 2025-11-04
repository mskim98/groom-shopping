package groom.backend.interfaces.product.dto.request;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.vo.Description;
import groom.backend.domain.product.model.vo.Name;
import groom.backend.domain.product.model.vo.Price;
import groom.backend.domain.product.model.vo.Stock;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        @NotBlank(message = "상품명은 필수입니다")
        @Size(min = 1, max = 100, message = "상품명은 1-100자 사이여야 합니다")
        String name,

        @Size(max = 1000, message = "상품 설명은 1000자 이하여야 합니다")
        String description,

        @NotNull(message = "가격은 필수입니다")
        @Min(value = 0, message = "가격은 0 이상이어야 합니다")
        Integer price,

        @NotNull(message = "재고는 필수입니다")
        @Min(value = 0, message = "재고는 0 이상이어야 합니다")
        Integer stock,

        @NotNull(message = "카테고리는 필수입니다")
        ProductCategory category
) {
    public Product toEntity() {
        return Product.create(
                new Name(this.name),
                new Description(this.description),
                new Price(this.price),
                new Stock(this.stock),
                this.category
        );
    }
}
