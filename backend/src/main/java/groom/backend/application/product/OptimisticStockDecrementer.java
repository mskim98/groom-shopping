package groom.backend.application.product;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 기존 @Version 낙관적 락 + @Retryable 경로. 기본 전략 */
@Component
@ConditionalOnProperty(name = "stock.decrease-strategy", havingValue = "optimistic", matchIfMissing = true)
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
