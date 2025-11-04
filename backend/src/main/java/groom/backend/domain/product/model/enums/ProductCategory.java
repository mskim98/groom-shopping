package groom.backend.domain.product.model.enums;

public enum ProductCategory {
    GENERAL("일반 품목"),
    TICKET("추첨 티켓");

    private final String description; // 필드 추가

    ProductCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isGeneral() {
        return this == GENERAL;
    }

    public boolean isTicket() {
        return this == TICKET;
    }
}
