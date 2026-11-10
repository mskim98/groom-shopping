package groom.backend.infrastructure.cache;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import java.util.UUID;

/** 상품 본문 - L2 에 JSON 으로 담기는 값. 재고와 상태는 일부러 뺀다(수량이 따로 1초 단위로 움직이므로 본문 버전과 섞이면 안 된다) */
public record ProductBody(UUID productId, String name, String description, Integer price,
                          ProductCategory category, String imageUrl, boolean flashSale) {

    public static ProductBody from(Product product) {
        return new ProductBody(product.getId(), product.getName(), product.getDescription(), product.getPrice(),
                product.getCategory(), product.getImageUrl(), product.isFlashSale());
    }
}
