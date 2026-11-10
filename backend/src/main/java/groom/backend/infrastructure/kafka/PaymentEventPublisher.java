package groom.backend.infrastructure.kafka;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Outbox 폴러: PENDING 이벤트를 주기적으로 읽어 Kafka 로 발행하고 PUBLISHED 로 전이한다.
// 발행 성공 후 상태 전이가 실패하면 다음 주기에 재발행되므로(at-least-once), 컨슈머가 Payment 상태로 멱등 처리한다.
// 인스턴스가 여럿이어도 같은 행을 동시에 발행하지 않는다 - 행을 FOR UPDATE SKIP LOCKED 로 집고 그 잠금을 PUBLISHED 표시 커밋까지 쥔다
// 대가: 발행하는 동안(정상이면 수 ms, Kafka 장애면 send 상한 40초) 한 커넥션을 쥔다
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final PaymentOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> paymentEventKafkaTemplate;

    // send().get() 에 상한이 없으면 Kafka 장애 때 폴러가 영원히 멈추고 같은 스케줄러의 다른 작업도 밀린다
    // producer 의 delivery.timeout(35초)보다 약간 길게 둔다 - 그보다 먼저 끊으면 발행은 성사됐는데 PENDING 으로 남아 중복 발행한다
    @Value("${payment.outbox.send-timeout-ms:40000}")
    private long sendTimeoutMs = 40000;

    private static final String TOPIC = "payment-events";
    private static final int BATCH_SIZE = 100;

    // @Scheduled(fixedDelay) : 이전 실행이 끝난 뒤 poll-delay 만큼 쉬고 다시 실행 (스캔 주기)
    // 기본 200ms - 승인 요청이 컨슈머 결과를 기다리는 동안 발행이 늦어지면 그만큼 응답이 늦는다
    @Scheduled(fixedDelayString = "${payment.outbox.poll-delay-ms:200}")
    @Transactional
    public void publishPending() {
        List<PaymentOutbox> batch = outboxRepository.findPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (PaymentOutbox outbox : batch) {
            try {
                // key = 결제 ID → 같은 결제 이벤트는 같은 파티션으로 라우팅(순서 보장)
                // topic 이 비어 있으면 결제 완료 이벤트(payment-events), 있으면 그 토픽(예: GIVEN_UP DLQ)
                String topic = outbox.getTopic() != null ? outbox.getTopic() : TOPIC;
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(topic, outbox.getAggregateId().toString(), outbox.getPayload());
                // 멱등 식별자: outbox.id 를 헤더로 실어 컨슈머가 중복을 거른다.
                record.headers().add("outbox-id", outbox.getId().toString().getBytes(StandardCharsets.UTF_8));

                // 발행 결과를 확인(get)해 성공한 건만 PUBLISHED 로 전이
                paymentEventKafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);

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
                // 이 회차는 여기서 끝낸다 - Kafka 장애는 건별이 아니라 브로커 전체라 남은 행도 같은 시간 초과를 겪는다(100건 x 40초)
                log.error("[OUTBOX_PUBLISH_FAILED] outboxId={}, error={}", outbox.getId(), e.getMessage());
                return;
            }
        }
    }
}
