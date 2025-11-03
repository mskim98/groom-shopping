package groom.backend.infrastructure.kafka;

import groom.backend.application.notification.NotificationApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer: 재고 임계값 이벤트를 수신하여 알림을 생성하고 SSE로 전송합니다.
 * 성능 측정을 위해 처리 전후 시간을 로깅합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockThresholdConsumer {

    private final NotificationApplicationService notificationService;

    @KafkaListener(topics = "stock-threshold-events", groupId = "notification-group")
    public void consumeStockThresholdEvent(StockThresholdEvent event) {
        long consumerReceiveTime = System.currentTimeMillis();
        long kafkaDeliveryTime = consumerReceiveTime - event.getTimestamp();
        
        log.info("[KAFKA_CONSUME_START] productId={}, currentStock={}, kafkaDeliveryTime={}ms, eventAge={}ms", 
                event.getProductId(), 
                event.getCurrentStock(), 
                kafkaDeliveryTime,
                consumerReceiveTime - event.getTimestamp());

        try {
            long notificationProcessStartTime = System.currentTimeMillis();
            
            // 알림 생성 및 SSE 전송
            notificationService.createAndSendNotifications(
                    event.getProductId(), 
                    event.getCurrentStock(), 
                    event.getThresholdValue()
            );
            
            long notificationProcessEndTime = System.currentTimeMillis();
            long notificationProcessDuration = notificationProcessEndTime - notificationProcessStartTime;
            long totalConsumerDuration = notificationProcessEndTime - consumerReceiveTime;
            long endToEndDuration = notificationProcessEndTime - event.getTimestamp();
            
            log.info("[KAFKA_CONSUME_SUCCESS] productId={}, notificationProcessDuration={}ms, totalConsumerDuration={}ms, endToEndDuration={}ms, kafkaDeliveryTime={}ms", 
                    event.getProductId(), 
                    notificationProcessDuration, 
                    totalConsumerDuration,
                    endToEndDuration,
                    kafkaDeliveryTime);
            
        } catch (Exception e) {
            long errorTime = System.currentTimeMillis();
            long errorDuration = errorTime - consumerReceiveTime;
            log.error("[KAFKA_CONSUME_FAILED] productId={}, duration={}ms, error={}", 
                    event.getProductId(), errorDuration, e.getMessage(), e);
            throw e;
        }
    }
}

