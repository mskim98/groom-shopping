package groom.backend.application.product;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 비관적 락 전략. SELECT FOR UPDATE 로 행을 선점해 충돌 대신 대기시킨다 */
@Component
@ConditionalOnProperty(name = "stock.decrease-strategy", havingValue = "pessimistic")
@RequiredArgsConstructor
public class PessimisticLockStockDecrementer implements StockDecrementer {

    private final SpringDataProductRepository springDataProductRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int decrease(UUID productId, int quantity) {
        ProductJpaEntity product = springDataProductRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.getStock() < quantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        product.setStock(product.getStock() - quantity);
        return product.getStock();
    }

    @Override
    public String strategyName() {
        return "pessimistic";
    }
}
