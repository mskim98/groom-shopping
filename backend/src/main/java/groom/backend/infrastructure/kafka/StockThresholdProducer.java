package groom.backend.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockThresholdProducer {

    private static final String TOPIC = "stock-threshold-events";
    private final KafkaTemplate<String, StockThresholdEvent> kafkaTemplate;

    /**
     * 재고 임계값 도달 이벤트를 Kafka에 발행합니다.
     * 성능 측정을 위해 발행 전후 시간을 로깅합니다.
     *
     * @param productId 제품 ID
     * @param currentStock 현재 재고
     * @param thresholdValue 임계값
     * @return 발행 시작 시간 (성능 측정용)
     */
    public long publishStockThresholdEvent(UUID productId, Integer currentStock, Integer thresholdValue) {
        long publishStartTime = System.currentTimeMillis();
        
        StockThresholdEvent event = new StockThresholdEvent(
                productId,
                currentStock,
                thresholdValue,
                publishStartTime  // 원본 이벤트 생성 시간
        );

        log.info("[KAFKA_PUBLISH_START] productId={}, currentStock={}, thresholdValue={}, eventTimestamp={}", 
                productId, currentStock, thresholdValue, publishStartTime);

        CompletableFuture<SendResult<String, StockThresholdEvent>> future = 
                kafkaTemplate.send(TOPIC, productId.toString(), event);

        long submitTime = System.currentTimeMillis();
        long submitDuration = submitTime - publishStartTime;
        
        log.info("[KAFKA_PUBLISH_SUBMIT] productId={}, submitDuration={}ms, note=Non-blocking async publish", 
                productId, submitDuration);

        future.whenComplete((result, ex) -> {
            long completeTime = System.currentTimeMillis();
            long totalDuration = completeTime - publishStartTime;
            long networkLatency = totalDuration - submitDuration;
            
            if (ex == null) {
                log.info("[KAFKA_PUBLISH_COMPLETE] productId={}, totalDuration={}ms, submitDuration={}ms, networkLatency={}ms, partition={}, offset={}", 
                        productId, 
                        totalDuration, 
                        submitDuration,
                        networkLatency,
                        result.getRecordMetadata().partition(), 
                        result.getRecordMetadata().offset());
            } else {
                log.error("[KAFKA_PUBLISH_FAILED] productId={}, duration={}ms, error={}", 
                        productId, totalDuration, ex.getMessage(), ex);
            }
        });

        return publishStartTime;
    }
}



