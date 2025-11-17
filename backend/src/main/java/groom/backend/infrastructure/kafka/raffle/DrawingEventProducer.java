package groom.backend.infrastructure.kafka.raffle;

import groom.backend.domain.raffle.entity.RaffleDrawingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class DrawingEventProducer {
    private static final String TOPIC = "drawing-execute-topic";

    private final KafkaTemplate<String, RaffleDrawingEvent> kafkaTemplate;

    /**
     * 추첨 실행 이벤트를 Kafka로 발행
     */
    public CompletableFuture<SendResult<String, RaffleDrawingEvent>> publishRaffleDrawingEvent(RaffleDrawingEvent event) {
        String key = String.valueOf(event.getRaffleId());

        CompletableFuture<SendResult<String, RaffleDrawingEvent>> future = kafkaTemplate.send(TOPIC, key, event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("추첨 실행 이벤트 발행 성공 - drawingId: {}, partition: {}, offset: {}",
                        event.getRaffleId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("추첨 실행 이벤트 발행 실패 - raffleId: {}", event.getRaffleId(), ex);
            }
        });

        return future;
    }
}
