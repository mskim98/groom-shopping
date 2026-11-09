package groom.backend.application.product;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// @Version 낙관적 락 + @Retryable 경로
//
// 기본 전략이었으나 2026-08-09 실측에서 셋 중 꼴찌라 기본값을 pessimistic 으로 넘겼다
// (성공률 94.56% / p95 548ms vs 비관적 100% / 101ms)
// 단일 상품 행에 부하가 몰려 충돌이 상시인 조건이라 재시도가 낭비가 된다
// 경합이 드문 워크로드에서는 순위가 뒤집히므로 전략 자체는 남겨 둔다
//
// ⚠ 이 전략은 시도마다 REQUIRES_NEW 로 독립 커밋해 결제 후처리 롤백에 합류하지 못한다 - 후처리가 실패하면
// DB 재고가 되돌아오지 않으므로 운영 기본값으로 쓰지 않고 3자 실측 비교 전용으로만 둔다
@Component
@ConditionalOnProperty(name = "stock.decrease-strategy", havingValue = "optimistic")
@RequiredArgsConstructor
public class OptimisticStockDecrementer implements StockDecrementer {

    private final ProductStockService productStockService;

    @Override
    public int decrease(UUID productId, int quantity) {
        return productStockService.decreaseWithOptimisticLock(productId, quantity);
    }

    @Override
    public String strategyName() {
        return "optimistic";
    }
}
