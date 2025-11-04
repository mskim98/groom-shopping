package groom.backend.domain.product.model.enums;

public enum ProductStatus {
    AVAILABLE("판매중"),
    OUT_OF_STOCK("품절");

    private final String description;

    ProductStatus(String description) {
        this.description = description;
    }

    public boolean isAvailable() {
        return this == AVAILABLE;
    }

    public boolean isOutOfStock() {
        return this == OUT_OF_STOCK;
    }
}
