package groom.backend.infrastructure.kafka;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 결제 이벤트 컨슈머. 멱등 처리(outbox.id 기준) 후 다운스트림(알림·정산·통계)으로 확장하는 지점.
// 현재는 기존 직접 알림 호출을 유지(Strangler)하고, 여기서는 멱등·로깅까지만 담당한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    // 기본 RedisTemplate<String,String> 빈 재사용 (중복 처리 방지용 Set)
    private final RedisTemplate<String, String> redisTemplate;

    private static final String DEDUP_KEY = "payment-event:processed";

    @KafkaListener(
            topics = "payment-events",
            groupId = "payment-event-group",
            containerFactory = "paymentEventKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        String outboxId = extractOutboxId(record);

        // 멱등: 이미 처리한 outboxId 면 무시. SADD 결과가 0이면 이미 존재(중복).
        if (outboxId != null) {
            Long added = redisTemplate.opsForSet().add(DEDUP_KEY, outboxId);
            if (added != null && added == 0L) {
                log.info("[PAYMENT_EVENT_DUPLICATE] skip outboxId={}", outboxId);
                return;
            }
        }

        log.info("[PAYMENT_EVENT_CONSUMED] outboxId={}, key={}, payload={}",
                outboxId, record.key(), record.value());
        // TODO 다운스트림 확장: 알림/정산/통계 컨슈머가 이 이벤트를 구독 (결제 도메인 무수정)
    }

    // Kafka 헤더에서 멱등 식별자(outbox-id)를 꺼낸다.
    private String extractOutboxId(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("outbox-id");
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
