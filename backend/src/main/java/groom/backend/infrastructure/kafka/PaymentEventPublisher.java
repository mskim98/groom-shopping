package groom.backend.infrastructure.kafka;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Outbox 폴러: PENDING 이벤트를 주기적으로 읽어 Kafka 로 발행하고 PUBLISHED 로 전이한다.
// 발행 성공 후 상태 전이가 실패하면 다음 주기에 재발행되므로(at-least-once), 컨슈머가 멱등 처리한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final PaymentOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> paymentEventKafkaTemplate;

    private static final String TOPIC = "payment-events";
    private static final int BATCH_SIZE = 100;

    // @Scheduled(fixedDelay) : 이전 실행이 끝난 뒤 1초 후 다시 실행 (스캔 주기)
    @Scheduled(fixedDelay = 1000)
    public void publishPending() {
        List<PaymentOutbox> batch = outboxRepository.findPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (PaymentOutbox outbox : batch) {
            try {
                // key = 결제 ID → 같은 결제 이벤트는 같은 파티션으로 라우팅(순서 보장)
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(TOPIC, outbox.getAggregateId().toString(), outbox.getPayload());
                // 멱등 식별자: outbox.id 를 헤더로 실어 컨슈머가 중복을 거른다.
                record.headers().add("outbox-id", outbox.getId().toString().getBytes(StandardCharsets.UTF_8));

                // 발행 결과를 확인(get)해 성공한 건만 PUBLISHED 로 전이
                paymentEventKafkaTemplate.send(record).get();

                outbox.markPublished();
                outboxRepository.save(outbox);
                log.info("[OUTBOX_PUBLISHED] outboxId={}, aggregateId={}", outbox.getId(), outbox.getAggregateId());
            } catch (InterruptedException e) {
                // 인터럽트는 플래그를 복원하고 즉시 중단
                Thread.currentThread().interrupt();
                log.error("[OUTBOX_PUBLISH_FAILED] interrupted, outboxId={}", outbox.getId());
                return;
            } catch (Exception e) {
                // 실패 건은 PENDING 으로 남겨 다음 주기에 재시도 (at-least-once)
                log.error("[OUTBOX_PUBLISH_FAILED] outboxId={}, error={}", outbox.getId(), e.getMessage());
            }
        }
    }
}
