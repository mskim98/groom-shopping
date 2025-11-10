package groom.backend.application.product;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.infrastructure.kafka.StockThresholdProducer;
import groom.backend.interfaces.cart.persistence.CartItemJpaEntity;
import groom.backend.interfaces.cart.persistence.SpringDataCartItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 제품 구매 및 재고 관리를 담당하는 Application Service입니다.
 * 구매 시 재고를 차감하고, 임계값에 도달하면 Kafka로 이벤트를 발행합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductApplicationService {

    private final ProductRepository productRepository;
    private final StockThresholdProducer stockThresholdProducer;
    private final SpringDataCartItemRepository cartItemRepository;

    /**
     * 제품을 구매합니다.
     * 재고를 차감하고, 임계값에 도달하면 Kafka로 알림 이벤트를 발행합니다.
     *
     * @param productId 제품 ID
     * @param quantity 구매 수량
     * @return 구매 결과 정보
     */
    @Transactional
    public PurchaseResult purchaseProduct(UUID productId, Integer quantity) {
        long purchaseStartTime = System.currentTimeMillis();
        log.info("[PURCHASE_API_START] productId={}, quantity={}, timestamp={}", 
                productId, quantity, purchaseStartTime);

        try {
            // 1. 제품 조회
            long queryStartTime = System.currentTimeMillis();
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new IllegalArgumentException("제품을 찾을 수 없습니다."));
            long queryDuration = System.currentTimeMillis() - queryStartTime;
            log.info("[PURCHASE_QUERY] productId={}, queryDuration={}ms", productId, queryDuration);

            // 2. 재고 확인 및 차감
            int stockBeforePurchase = product.getStock();
            product.reduceStock(quantity);
            int stockAfterPurchase = product.getStock();
            
            long reduceStartTime = System.currentTimeMillis();
            productRepository.save(product);
            long reduceDuration = System.currentTimeMillis() - reduceStartTime;
            log.info("[PURCHASE_STOCK_REDUCED] productId={}, stockBefore={}, stockAfter={}, reduceDuration={}ms", 
                    productId, stockBeforePurchase, stockAfterPurchase, reduceDuration);

            // 3. 임계값 확인 및 Kafka 이벤트 발행 (비동기)
            boolean thresholdReached = product.isStockBelowThreshold();
            
            long kafkaPublishStartTime = System.currentTimeMillis();
            if (thresholdReached && product.canNotify()) {
                // Kafka 발행 시작 (비동기 - 즉시 반환)
                stockThresholdProducer.publishStockThresholdEvent(
                        productId,
                        stockAfterPurchase,
                        product.getThresholdValue()
                );
                
                long kafkaPublishSubmitTime = System.currentTimeMillis();
                long kafkaSubmitDuration = kafkaPublishSubmitTime - kafkaPublishStartTime;
                
                log.info("[PURCHASE_KAFKA_SUBMIT] productId={}, submitDuration={}ms, currentStock={}, thresholdValue={}, note=ASYNC_NON_BLOCKING", 
                        productId, kafkaSubmitDuration, stockAfterPurchase, product.getThresholdValue());
            }

            long purchaseApiEndTime = System.currentTimeMillis();
            long purchaseApiDuration = purchaseApiEndTime - purchaseStartTime;
            
            log.info("[PURCHASE_API_SUCCESS] productId={}, quantity={}, apiResponseTime={}ms, stockBefore={}, stockAfter={}, thresholdReached={}, note=Notification processed asynchronously via Kafka", 
                    productId, quantity, purchaseApiDuration, stockBeforePurchase, stockAfterPurchase, thresholdReached);

            return new PurchaseResult(
                    productId,
                    quantity,
                    stockAfterPurchase,
                    thresholdReached,
                    product.getThresholdValue()
            );

        } catch (Exception e) {
            long errorDuration = System.currentTimeMillis() - purchaseStartTime;
            log.error("[PURCHASE_FAILED] productId={}, quantity={}, duration={}ms, error={}", 
                    productId, quantity, errorDuration, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 제품의 재고를 단순 차감합니다.
     * 알림 전송 없이 재고만 차감합니다.
     *
     * @param productId 제품 ID
     * @param quantity 차감할 수량
     * @return 차감 후 재고 수량
     */
    @Transactional
    public Integer reduceStock(UUID productId, Integer quantity) {
        long reduceStartTime = System.currentTimeMillis();
        log.info("[STOCK_REDUCE_START] productId={}, quantity={}, timestamp={}", 
                productId, quantity, reduceStartTime);

        try {
            // 1. 제품 조회
            long queryStartTime = System.currentTimeMillis();
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new IllegalArgumentException("제품을 찾을 수 없습니다."));
            long queryDuration = System.currentTimeMillis() - queryStartTime;
            log.info("[STOCK_REDUCE_QUERY] productId={}, queryDuration={}ms", productId, queryDuration);

            // 2. 재고 차감
            int stockBefore = product.getStock();
            product.reduceStock(quantity);
            int stockAfter = product.getStock();
            
            long saveStartTime = System.currentTimeMillis();
            productRepository.save(product);
            long saveDuration = System.currentTimeMillis() - saveStartTime;
            
            log.info("[STOCK_REDUCE_SUCCESS] productId={}, stockBefore={}, stockAfter={}, quantity={}, saveDuration={}ms", 
                    productId, stockBefore, stockAfter, quantity, saveDuration);

            return stockAfter;

        } catch (Exception e) {
            long errorDuration = System.currentTimeMillis() - reduceStartTime;
            log.error("[STOCK_REDUCE_FAILED] productId={}, quantity={}, duration={}ms, error={}", 
                    productId, quantity, errorDuration, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 사용자의 장바구니에 담긴 모든 제품을 구매합니다.
     *
     * @param userId 사용자 ID
     * @return 구매 결과 리스트
     */
    @Transactional
    public List<PurchaseResult> purchaseCartItems(Long userId) {
        long cartPurchaseStartTime = System.currentTimeMillis();
        log.info("[CART_PURCHASE_START] userId={}, timestamp={}", userId, cartPurchaseStartTime);

        // 1. 사용자의 장바구니 항목 조회
        List<CartItemJpaEntity> cartItems = cartItemRepository.findByUserId(userId);

        if (cartItems.isEmpty()) {
            log.info("[CART_PURCHASE_EMPTY] userId={}", userId);
            throw new IllegalArgumentException("장바구니가 비어있습니다.");
        }

        List<PurchaseResult> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        // 2. 각 장바구니 항목을 구매
        for (CartItemJpaEntity cartItem : cartItems) {
            try {
                UUID productId = cartItem.getProductId();
                Integer quantity = cartItem.getQuantity();

                log.info("[CART_PURCHASE_ITEM] userId={}, productId={}, quantity={}", 
                        userId, productId, quantity);

                PurchaseResult result = purchaseProduct(productId, quantity);
                results.add(result);
                successCount++;

                log.info("[CART_PURCHASE_ITEM_SUCCESS] userId={}, productId={}, quantity={}", 
                        userId, productId, quantity);

            } catch (Exception e) {
                log.error("[CART_PURCHASE_ITEM_FAILED] userId={}, productId={}, quantity={}, error={}", 
                        userId, cartItem.getProductId(), cartItem.getQuantity(), e.getMessage(), e);
                failCount++;
            }
        }

        // 3. 구매 완료 후 장바구니 비우기 (성공한 항목만)
        if (successCount > 0) {
            try {
                // 구매 성공한 제품들의 cartItem만 삭제
                for (CartItemJpaEntity cartItem : cartItems) {
                    UUID productId = cartItem.getProductId();
                    // 구매 결과에 있는 제품인지 확인
                    boolean purchaseSuccess = results.stream()
                            .anyMatch(r -> r.getProductId().equals(productId));
                    
                    if (purchaseSuccess) {
                        cartItemRepository.delete(cartItem);
                        log.info("[CART_ITEM_DELETED] userId={}, productId={}, cartItemId={}", 
                                userId, productId, cartItem.getId());
                    }
                }
            } catch (Exception e) {
                log.error("[CART_CLEAR_FAILED] userId={}, error={}", userId, e.getMessage(), e);
            }
        }

        long cartPurchaseEndTime = System.currentTimeMillis();
        long cartPurchaseDuration = cartPurchaseEndTime - cartPurchaseStartTime;

        log.info("[CART_PURCHASE_COMPLETE] userId={}, totalItems={}, successCount={}, failCount={}, duration={}ms", 
                cartItems.size(), successCount, failCount, cartPurchaseDuration);

        if (results.isEmpty()) {
            throw new IllegalArgumentException("구매할 수 있는 제품이 없습니다.");
        }

        return results;
    }

    /**
     * 사용자의 장바구니에서 모든 제품을 제거합니다.
     * 구매 로직 없이 장바구니 제거만 수행합니다.
     *
     * @param userId 사용자 ID
     */
    @Transactional
    public void clearCartItems(Long userId) {
        long clearStartTime = System.currentTimeMillis();
        log.info("[CART_CLEAR_START] userId={}, timestamp={}", userId, clearStartTime);

        try {
            // 1. 사용자의 장바구니 항목 조회
            List<CartItemJpaEntity> cartItems = cartItemRepository.findByUserId(userId);

            if (cartItems.isEmpty()) {
                log.info("[CART_CLEAR_NO_ITEMS] userId={}", userId);
                return;
            }

            int deletedCount = 0;

            // 2. 모든 장바구니 항목 삭제
            for (CartItemJpaEntity cartItem : cartItems) {
                UUID productId = cartItem.getProductId();
                cartItemRepository.delete(cartItem);
                deletedCount++;
                log.info("[CART_ITEM_DELETED] userId={}, productId={}, cartItemId={}", 
                        userId, productId, cartItem.getId());
            }

            long clearEndTime = System.currentTimeMillis();
            long clearDuration = clearEndTime - clearStartTime;

            log.info("[CART_CLEAR_COMPLETE] userId={}, totalItems={}, deletedCount={}, duration={}ms",
                    cartItems.size(), deletedCount, clearDuration);

        } catch (Exception e) {
            long errorDuration = System.currentTimeMillis() - clearStartTime;
            log.error("[CART_CLEAR_FAILED] userId={}, duration={}ms, error={}", 
                    userId, errorDuration, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 구매 결과를 담는 내부 클래스
     */
    public static class PurchaseResult {
        private final UUID productId;
        private final Integer quantity;
        private final Integer remainingStock;
        private final Boolean stockThresholdReached;
        private final Integer thresholdValue;

        public PurchaseResult(UUID productId, Integer quantity, Integer remainingStock, Boolean stockThresholdReached, Integer thresholdValue) {
            this.productId = productId;
            this.quantity = quantity;
            this.remainingStock = remainingStock;
            this.stockThresholdReached = stockThresholdReached;
            this.thresholdValue = thresholdValue;
        }

        public UUID getProductId() { return productId; }
        public Integer getQuantity() { return quantity; }
        public Integer getRemainingStock() { return remainingStock; }
        public Boolean getStockThresholdReached() { return stockThresholdReached; }
        public Integer getThresholdValue() { return thresholdValue; }
    }
}
