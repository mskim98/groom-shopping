package groom.backend.domain.product.entity;

import lombok.Getter;

import java.util.UUID;

@Getter
public class Product {
    private final UUID id;
    private String name;
    private String description;
    private Integer price;
    private Integer stock;
    private Integer thresholdValue; // 재고 임계값
    private Boolean isActive;
    private String category;

    public Product(UUID id, String name, String description, Integer price, Integer stock, Integer thresholdValue, Boolean isActive, String category) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.thresholdValue = thresholdValue;
        this.isActive = isActive;
        this.category = category;
    }

    public void reduceStock(int quantity) {
        if (this.stock < quantity) {
            throw new IllegalArgumentException("재고가 부족합니다.");
        }
        this.stock -= quantity;
    }

    public boolean isStockBelowThreshold() {
        return this.stock <= this.thresholdValue;
    }

    public boolean canNotify() {
        return this.stock > 0 && this.isStockBelowThreshold();
    }
}

