package groom.backend.infrastructure.cache;

import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import jakarta.persistence.PostUpdate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * product.stock · product.stock_mode 를 바꾸는 쓰기가 커밋된 뒤에 수량 L2 항목을 지운다
 *
 * <p>커밋 전에 지우면 그 사이에 읽은 요청이 옛 값을 L2 에 다시 채워 L2 만료까지 남는다. 그래서 커밋 뒤 단계에 건다.
 * 쓰기 지점마다 직접 부르는 곳은 {@code StockLedgerRepository} 의 UPDATE 문과 {@code ConditionalUpdateStockDecrementer} 이고,
 * 엔티티 저장은 {@link ProductJpaEntity} 의 엔티티 리스너({@link #onProductUpdated})가 맡는다
 *
 * <p>shortcut: 상품 행의 다른 열(이름 등)만 바뀐 저장에도 지운다, 지운 뒤 첫 읽기가 DB 를 한 번 더 읽을 뿐이라 열별 판별은 두지 않는다
 */
@Component
@RequiredArgsConstructor
public class StockQuantityInvalidator {

    private final ProductDetailCache cache;

    /** 트랜잭션이 있으면 커밋 뒤에, 없으면 바로 지운다. 롤백되면 지우지 않는다 */
    public void evictAfterCommit(UUID productId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evictQuantity(productId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evictQuantity(productId);
            }
        });
    }

    @PostUpdate
    void onProductUpdated(ProductJpaEntity entity) {
        evictAfterCommit(entity.getId());
    }
}
