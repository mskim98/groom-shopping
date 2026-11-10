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
 * 재고 복원(증가) 동시성 제어 서비스. 차감은 조건부 UPDATE(일반 상품) · 원장(플래시 상품)이 맡아 이 서비스를 거치지 않는다.
 * <p>
 * 낙관적 락(@Version) 충돌은 짧은 순간의 경합이라 자동 재시도로 대부분 해소된다. 플래시 상품은 적용 배치가 같은 행의 version 을 올려 충돌할 수 있다.
 * 재시도가 동작하려면 '재시도는 트랜잭션 바깥, 트랜잭션은 안쪽' 이어야 한다 —
 * 충돌은 트랜잭션 커밋 시점에 발생하므로, 매 시도마다 새 트랜잭션(REQUIRES_NEW)이 열려 커밋·충돌이
 * 재시도 메서드 프레임에서 잡혀야 한다. 그래서 selfProvider 로 프록시를 거쳐 내부 트랜잭션 메서드를 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductStockService {

    private final ProductRepository productRepository;
    // 자기호출 우회: increaseOnce 의 @Transactional(REQUIRES_NEW) 가 프록시를 거쳐 적용되도록 자기 자신을 주입.
    private final ObjectProvider<ProductStockService> selfProvider;

    /**
     * 낙관적 락 기반 재고 증가 (충돌 시 자동 재시도). 결제 취소 복구에서 쓴다.
     * <p>
     * '재시도 바깥 / REQUIRES_NEW 안쪽' 구조라 @Version 충돌이 재시도 메서드 프레임에서 잡힌다.
     */
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2, random = true, maxDelay = 1000),
            listeners = "stockRetryListener" // 재시도 횟수 Micrometer 노출(정량 실측)
    )
    public void increaseWithOptimisticLock(UUID productId, int quantity) {
        // 프록시 경유 호출이라야 REQUIRES_NEW 가 매 시도마다 새 트랜잭션을 연다.
        selfProvider.getObject().increaseOnce(productId, quantity);
    }

    /**
     * 단일 증가 트랜잭션 (재시도 1회 단위). 커밋 시 version 이 맞지 않으면 충돌 예외가 발생한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void increaseOnce(UUID productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.increaseStock(quantity);
        productRepository.save(product);
    }

    /**
     * 재시도 한도(3회) 소진 시 복구 메서드. 사용자 가시 예외로 변환한다.
     */
    @Recover
    public void recoverIncrease(ObjectOptimisticLockingFailureException e, UUID productId, int quantity) {
        log.error("[STOCK_RESTORE_GIVEUP] 재고 증가(복원) 재시도 실패 - productId={}, quantity={}", productId, quantity);
        throw new BusinessException(ErrorCode.PRODUCT_STOCK_CONFLICT);
    }
}
