package groom.backend.application.product;

import java.util.UUID;

/**
 * 재고 차감 전략. 낙관적 락 / 조건부 UPDATE / 비관적 락을 같은 부하로 비교하기 위한 공통 진입점
 */
public interface StockDecrementer {

    /** 재고를 차감하고 차감 후 재고량을 반환한다 */
    int decrease(UUID productId, int quantity);

    /** 측정·로그 태깅용 전략 이름 */
    String strategyName();
}
