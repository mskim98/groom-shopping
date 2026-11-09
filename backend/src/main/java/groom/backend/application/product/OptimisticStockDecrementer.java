package groom.backend.application.product;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// @Version 낙관적 락 전략
//
// 기본 전략이었으나 2026-08-09 실측에서 셋 중 꼴찌라 기본값을 pessimistic 으로 넘겼다
// (성공률 94.56% / p95 548ms vs 비관적 100% / 101ms)
// 단일 상품 행에 부하가 몰려 충돌이 상시인 조건이라 재시도가 낭비가 된다
// 경합이 드문 워크로드에서는 순위가 뒤집히므로 전략 자체는 남겨 둔다
//
// 결제 후처리 트랜잭션(applyApprovedPaymentInTx)에 합류한다(REQUIRED). 예전에는 시도마다 REQUIRES_NEW 로 독립 커밋해
// 후처리가 롤백돼도 재고만 차감된 채 남았다(R9 재고 누수). 합류하면 version 충돌 예외가 트랜잭션 전체를 롤백 전용으로
// 만들어 이 안에서는 재시도할 수 없으므로, 충돌 재시도는 후처리 트랜잭션 전체를 다시 도는 쪽
// (PaymentApplicationService.applyWithRetry)이 맡는다
@Component
@ConditionalOnProperty(name = "stock.decrease-strategy", havingValue = "optimistic")
@RequiredArgsConstructor
public class OptimisticStockDecrementer implements StockDecrementer {

    private final ProductRepository productRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public int decrease(UUID productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        try {
            product.decreaseStock(quantity);
        } catch (IllegalArgumentException insufficient) {
            // 도메인이 재고 부족을 IllegalArgumentException 으로 알린다 - 다른 전략과 같은 오류 코드로 맞춘다
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        // save → UPDATE ... WHERE id=? AND version=? (낙관적 락). 충돌 시 flush·커밋 시점에 예외
        return productRepository.save(product).getStock();
    }

    @Override
    public String strategyName() {
        return "optimistic";
    }
}
