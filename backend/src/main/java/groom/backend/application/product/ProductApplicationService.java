package groom.backend.application.product;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 흐름 밖에서 상품 재고를 직접 낮추는 유일한 진입점을 가진 Application Service 입니다.
 * 결제 없는 구매 경로는 없앴다 - 재고는 결제 승인(플래시 상품은 원장, 그 밖의 상품은 후처리의 조건부 UPDATE)으로만 줄고,
 * 이 서비스는 추첨 경품 재고({@code RaffleDrawApplicationService})만 낮춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductApplicationService {

    private final ProductRepository productRepository;

    /**
     * 추첨 경품 재고를 차감합니다. 플래시 세일 상품은 거절합니다 - 이 경로는 Redis 선점과 원장을 거치지 않아
     * 남은 재고(product.stock − 미적용 원장 합)와 Redis 키를 어긋나게 하기 때문입니다(관리자 재고 변경과 같은 이유 · 같은 오류).
     *
     * @param productId 제품 ID
     * @param quantity 차감할 수량
     * @return 차감 후 재고 수량
     */
    @Transactional
    public Integer reduceStock(UUID productId, Integer quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("제품을 찾을 수 없습니다."));
        if (product.isFlashSale()) {
            throw new BusinessException(ErrorCode.CONFLICT, "플래시 세일 상품의 재고는 직접 바꿀 수 없습니다.");
        }

        int stockBefore = product.getStock();
        product.reduceStock(quantity);
        productRepository.save(product);
        int stockAfter = product.getStock();

        log.info("[STOCK_REDUCE_SUCCESS] productId={}, stockBefore={}, stockAfter={}, quantity={}",
                productId, stockBefore, stockAfter, quantity);
        return stockAfter;
    }
}
