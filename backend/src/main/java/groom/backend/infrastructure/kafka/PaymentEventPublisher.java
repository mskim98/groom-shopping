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

// Outbox 폴러: PENDING 이벤트를 주기적으로 집어 Kafka 로 발행하고 PUBLISHED 로 표시한다
// 트랜잭션 없이 돈다 - 집기(임대 설정)와 PUBLISHED 표시가 각자 한 문장짜리 짧은 트랜잭션이라 send 를 기다리는 동안(Kafka 장애면 최대 40초) DB 커넥션을 쥐지 않는다
// 인스턴스가 여럿이어도 같은 행을 동시에 발행하지 않는다 - 집은 행은 lease-ms 동안 임대돼 다른 폴러가 건너뛴다
// 발행이 실패하면 그 행과 보내지 못한 나머지 행의 임대를 바로 풀어 다음 회차가 created_at 순서로 다시 집는다
// 발행 성공 뒤 PUBLISHED 표시가 실패하거나 폴러가 죽으면 임대가 지난 뒤 다시 발행된다(at-least-once). 컨슈머가 outbox-id 헤더와 Payment 상태로 멱등 처리한다
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

    // 집은 행의 임대 시간. send 상한(40초)보다 길어야 발행 중인 행을 다른 폴러가 가로채 중복 발행하지 않고,
    // 너무 길면 폴러가 죽거나 send 가 실패한 행(과 같은 회차에 집힌 남은 행)이 그만큼 늦게 다시 발행된다
    @Value("${payment.outbox.lease-ms:60000}")
    private long leaseMs = 60000;

    private static final String TOPIC = "payment-events";
    private static final int BATCH_SIZE = 100;

    // @Scheduled(fixedDelay) : 이전 실행이 끝난 뒤 poll-delay 만큼 쉬고 다시 실행 (스캔 주기)
    // 기본 200ms - 승인 요청이 컨슈머 결과를 기다리는 동안 발행이 늦어지면 그만큼 응답이 늦는다
    @Scheduled(fixedDelayString = "${payment.outbox.poll-delay-ms:200}")
    public void publishPending() {
        // shortcut: 폴러가 집은 뒤 죽어 임대를 풀지 못한 행은 임대 시간만큼 기다리고 그동안 같은 결제의 더 새로운 행이 먼저 발행될 수 있다, 컨슈머가 결제 상태로 적용해 허용하며 순서가 필요한 소비자가 생기면 결제별 직렬 발행으로 바꾼다
        List<PaymentOutbox> batch = outboxRepository.claimPendingBatch(BATCH_SIZE, leaseMs);
        if (batch.isEmpty()) {
            return;
        }

        for (int i = 0; i < batch.size(); i++) {
            PaymentOutbox outbox = batch.get(i);
            try {
                // key = 결제 ID → 같은 결제 이벤트는 같은 파티션으로 라우팅(순서 보장)
                // topic 이 비어 있으면 결제 완료 이벤트(payment-events), 있으면 그 토픽(예: GIVEN_UP DLQ)
                String topic = outbox.getTopic() != null ? outbox.getTopic() : TOPIC;
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(topic, outbox.getAggregateId().toString(), outbox.getPayload());
                // 멱등 식별자: outbox.id 를 헤더로 실어 컨슈머가 중복을 거른다.
                record.headers().add("outbox-id", outbox.getId().toString().getBytes(StandardCharsets.UTF_8));

                // 발행 결과를 확인(get)해 성공한 건만 PUBLISHED 로 표시
                paymentEventKafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);

                outboxRepository.markPublished(outbox.getId());
                log.info("[OUTBOX_PUBLISHED] outboxId={}, aggregateId={}", outbox.getId(), outbox.getAggregateId());
            } catch (InterruptedException e) {
                // 인터럽트는 플래그를 복원하고 즉시 중단
                Thread.currentThread().interrupt();
                log.error("[OUTBOX_PUBLISH_FAILED] interrupted, outboxId={}", outbox.getId());
                releaseLease(batch, i);
                return;
            } catch (Exception e) {
                // 실패 건은 PENDING 으로 남고 임대는 아래에서 바로 푼다 (at-least-once)
                // 이 회차는 여기서 끝낸다 - Kafka 장애는 건별이 아니라 브로커 전체라 남은 행도 같은 시간 초과를 겪는다(100건 x 40초)
                log.error("[OUTBOX_PUBLISH_FAILED] outboxId={}, error={}", outbox.getId(), e.getMessage());
                releaseLease(batch, i);
                return;
            }
        }
    }

    // 실패한 행과 보내지 못한 나머지 행의 임대를 한 문장으로 푼다. 풀지 못하면 임대 시간이 지난 뒤에 다시 집힌다
    private void releaseLease(List<PaymentOutbox> batch, int from) {
        try {
            outboxRepository.releaseLease(batch.subList(from, batch.size()).stream().map(PaymentOutbox::getId).toList());
        } catch (Exception e) {
            log.error("[OUTBOX_LEASE_RELEASE_FAILED] 임대 시간이 지난 뒤 다시 집힘, count={}, error={}", batch.size() - from, e.getMessage());
        }
    }
}
