package groom.backend.application.product;

import groom.backend.domain.product.entity.Product;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.infrastructure.kafka.StockThresholdProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
