package groom.backend.domain.product.model;

import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.enums.ProductStatus;
import groom.backend.domain.product.model.vo.Description;
import groom.backend.domain.product.model.vo.Name;
import groom.backend.domain.product.model.vo.Price;
import groom.backend.domain.product.model.vo.Stock;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    private UUID id;
    private Name name;
    private Description description;
    private Price price;
    private Stock stock;
    private ProductStatus status;
    private ProductCategory category;
    private Integer thresholdValue;
    private Boolean isActive;

    public static Product create(
            UUID id,
            Name name,
            Description description,
            Price price,
            Stock stock,
            ProductCategory category,
            Integer thresholdValue,
            Boolean isActive) {
        Product product = new Product();
        product.id = id;
        product.name = name;
        product.description = description;
        product.price = price;
        product.stock = stock;
        product.category = category;
        product.thresholdValue = thresholdValue;
        product.isActive = isActive;
        product.status = stock.isEmpty()
                ? ProductStatus.OUT_OF_STOCK
                : ProductStatus.AVAILABLE;
        return product;
    }

    // 재고 차감 (기존 메서드명 호환)
    public void reduceStock(int quantity) {
        this.stock = this.stock.decrease(quantity);
        updateStatusByStock();
    }

    // 재고 차감
    public void decreaseStock(int quantity) {
        this.stock = this.stock.decrease(quantity);
        updateStatusByStock();
    }

    // 재고 증가
    public void increaseStock(int quantity) {
        this.stock = this.stock.increase(quantity);
        updateStatusByStock();
    }

    // 재고에 따른 상태 업데이트
    // 수정 후 재고 0 인 경우 OUT_OF_Stock
    // 재고 0 이 아닌 양수면 AVAILABLE
    private void updateStatusByStock() {
        if (this.stock.isEmpty()) {
            this.status = ProductStatus.OUT_OF_STOCK;
        } else {
            this.status = ProductStatus.AVAILABLE;
        }
    }

    // 가격 변경
    public void changePrice(Price newPrice) {
        this.price = newPrice;
    }

    // 상품명 변경
    public void changeName(Name newName) {
        this.name = newName;
    }

    // 설명 변경
    public void changeDescription(Description newDescription) {
        this.description = newDescription;
    }

    // 카테고리 변경
    public void changeCategory(ProductCategory newCategory) {
        this.category = newCategory;
    }

    // 재고 임계값 확인
    public boolean isStockBelowThreshold() {
        if (thresholdValue == null) {
            return false;
        }
        return this.stock.getAmount() <= this.thresholdValue;
    }

    // 알림 가능 여부 확인
    public boolean canNotify() {
        return this.isActive != null && this.isActive
                && !this.stock.isEmpty()
                && isStockBelowThreshold();
    }

    // 편의 메서드: stock 값 직접 접근
    public Integer getStock() {
        return this.stock.getAmount();
    }

    // 편의 메서드: name 값 직접 접근
    public String getName() {
        return this.name != null ? this.name.getValue() : null;
    }

    // 편의 메서드: description 값 직접 접근
    public String getDescription() {
        return this.description != null ? this.description.getValue() : null;
    }

    // 편의 메서드: price 값 직접 접근
    public Integer getPrice() {
        return this.price != null ? this.price.getValue() : null;
    }
}
