package groom.backend.application.product;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조건부 UPDATE 전략. WHERE stock >= :quantity 로 부족을 DB 가 판정하므로
 * 락도 재시도도 필요 없다 (쿠폰의 decreaseQuantityAtomically 와 같은 패턴)
 */
@Component
@ConditionalOnProperty(name = "stock.decrease-strategy", havingValue = "conditional")
@RequiredArgsConstructor
public class ConditionalUpdateStockDecrementer implements StockDecrementer {

    private final SpringDataProductRepository springDataProductRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int decrease(UUID productId, int quantity) {
        int affected = springDataProductRepository.decreaseStockIfEnough(productId, quantity);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        return springDataProductRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND))
                .getStock();
    }

    @Override
    public String strategyName() {
        return "conditional";
    }
}
