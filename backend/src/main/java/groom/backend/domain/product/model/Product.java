package groom.backend.domain.product.model;

import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.enums.ProductStatus;
import groom.backend.domain.product.model.vo.Description;
import groom.backend.domain.product.model.vo.Name;
import groom.backend.domain.product.model.vo.Price;
import groom.backend.domain.product.model.vo.Stock;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    private Name name;

    @Embedded
    private Description description;

    @Embedded
    private Price price;

    @Embedded
    private Stock stock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductCategory category;

    public static Product create(
            Name name,
            Description description,
            Price price,
            Stock stock,
            ProductCategory category) {
        Product product = new Product();
        product.name = name;
        product.description = description;
        product.price = price;
        product.stock = stock;
        product.category = category;
        product.status = stock.isEmpty()
                ? ProductStatus.OUT_OF_STOCK
                : ProductStatus.AVAILABLE;
        return product;
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
}
