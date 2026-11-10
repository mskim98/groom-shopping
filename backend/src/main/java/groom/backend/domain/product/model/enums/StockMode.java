package groom.backend.domain.product.model.enums;

// 플래시 세일 상품의 재고 판정 위치. REDIS 는 Redis 선점이 판정하고, DB 는 Redis 장애 동안 product 행의 조건부 UPDATE 가 판정한다
public enum StockMode {
    REDIS,
    DB
}
