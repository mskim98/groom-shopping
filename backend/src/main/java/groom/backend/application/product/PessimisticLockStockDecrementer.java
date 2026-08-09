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

// 비관적 락 전략. SELECT FOR UPDATE 로 행을 선점해 충돌 대신 대기시킨다
//
// 2026-08-09 실측(VU 20 · 1만건 · 연속)에서 셋 중 1위라 기본 전략으로 올렸다
// 성공률 100% / 평균 64.8ms / p95 100.9ms / 재시도 0
// 조건부 UPDATE 를 안 고른 이유는 재고가 상품 엔티티의 한 필드라 가격·판매 상태와
// @Version 하나를 공유하기 때문이다 - 재고만 조건부 UPDATE 로 빼면 나머지 필드의
// 동시 수정 보호가 사라진다. 그쪽은 재고 테이블 분리가 선행돼야 한다
//
// 확정 범위는 고경합 · 짧은 임계 구역까지다. 경합이 드물면 순위가 뒤집힌다
@Component
@ConditionalOnProperty(name = "stock.decrease-strategy", havingValue = "pessimistic", matchIfMissing = true)
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
