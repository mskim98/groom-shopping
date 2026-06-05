package groom.backend.application.product;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 차감 동시성 제어 서비스.
 * <p>
 * 낙관적 락(@Version) 충돌은 짧은 순간의 경합이라 자동 재시도로 대부분 해소된다.
 * 재시도가 동작하려면 '재시도는 트랜잭션 바깥, 트랜잭션은 안쪽' 이어야 한다 —
 * 충돌은 트랜잭션 커밋 시점에 발생하므로, 매 시도마다 새 트랜잭션(REQUIRES_NEW)이 열려 커밋·충돌이
 * 재시도 메서드 프레임에서 잡혀야 한다. 그래서 selfProvider 로 프록시를 거쳐 내부 트랜잭션 메서드를 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductStockService {

    private final ProductRepository productRepository;
    // 자기호출 우회: decreaseOnce 의 @Transactional(REQUIRES_NEW) 가 프록시를 거쳐 적용되도록 자기 자신을 주입.
    private final ObjectProvider<ProductStockService> selfProvider;

    /**
     * 낙관적 락 기반 재고 차감 (충돌 시 자동 재시도). 차감 후 재고량을 반환.
     */
    // @Retryable : 충돌(ObjectOptimisticLockingFailureException) 시 최대 3회, 100ms·200ms·400ms 백오프로 재시도.
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public int decreaseWithOptimisticLock(UUID productId, int quantity) {
        // 프록시 경유 호출이라야 REQUIRES_NEW 가 매 시도마다 새 트랜잭션을 연다.
        return selfProvider.getObject().decreaseOnce(productId, quantity);
    }

    /**
     * 단일 차감 트랜잭션 (재시도 1회 단위). 커밋 시 version 이 맞지 않으면 충돌 예외가 발생한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int decreaseOnce(UUID productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.decreaseStock(quantity);
        // save → UPDATE ... WHERE id=? AND version=? (낙관적 락). 충돌 시 커밋 시점에 예외.
        return productRepository.save(product).getStock();
    }

    /**
     * 재시도 한도(3회)를 모두 소진한 뒤 호출되는 복구 메서드. 사용자 가시 예외로 변환한다.
     */
    // @Recover : 첫 인자는 재시도 대상 예외, 나머지는 원본 메서드 시그니처와 동일해야 한다.
    @Recover
    public int recover(ObjectOptimisticLockingFailureException e, UUID productId, int quantity) {
        log.error("[STOCK_CONFLICT_GIVEUP] 재고 차감 재시도 실패 - productId={}, quantity={}", productId, quantity);
        throw new BusinessException(ErrorCode.PRODUCT_STOCK_CONFLICT);
    }
}
