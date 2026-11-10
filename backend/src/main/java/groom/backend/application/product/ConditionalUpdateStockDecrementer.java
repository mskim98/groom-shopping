package groom.backend.application.product;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플래시 세일이 아닌 상품의 재고 차감. WHERE stock >= :quantity 로 부족을 DB 가 판정하므로
 * 락도 재시도도 필요 없다 (쿠폰의 decreaseQuantityAtomically 와 같은 패턴)
 */
@Component
@RequiredArgsConstructor
public class ConditionalUpdateStockDecrementer {

    private final SpringDataProductRepository springDataProductRepository;

    // REQUIRED : 결제 후처리 트랜잭션에 합류해 후처리 실패 시 차감도 함께 롤백된다
    /** 재고를 차감하고 차감 후 재고량을 반환한다. 부족하면 INSUFFICIENT_STOCK */
    @Transactional(propagation = Propagation.REQUIRED)
    public int decrease(UUID productId, int quantity) {
        int affected = springDataProductRepository.decreaseStockIfEnough(productId, quantity);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        return springDataProductRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND))
                .getStock();
    }
}
