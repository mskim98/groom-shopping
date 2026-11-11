package groom.backend.infrastructure.kafka;

import groom.backend.application.payment.ConfirmStageTimers;
import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Outbox 폴러: PENDING 이벤트를 주기적으로 집어 Kafka 로 발행하고 PUBLISHED 로 표시한다
// 한 회차(최대 100행)를 모두 보낸 뒤 확인을 한 번에 기다리고, 확인된 행을 한 문장으로 PUBLISHED 표시한다
// 행마다 send 확인을 기다리고 UPDATE 하던 때는 한 회차가 Kafka · PostgreSQL 왕복 100번이라 뒤의 행이 기다렸다 - r121 부하 측정에서 outbox 행 생성 → 폴러가 집기까지(outbox_wait_claim)가 초당 1,500건에 평균 3.8~4.0초였다
// 프로듀서가 보낸 행을 묶어 한꺼번에 내보내므로 한 회차가 왕복 한 번 값이다
// 같은 결제의 행은 키(결제 ID)가 같아 같은 파티션으로 가고, payment-events 프로듀서의 멱등 전송(enable.idempotence)과 in-flight 5 이하가 재시도가 있어도 집은 순서(created_at)대로 쌓이게 한다
// 한 결제의 앞 행 확인이 실패하면 같은 회차의 뒤 행은 확인이 와도 PUBLISHED 로 표시하지 않고 임대를 풀어 실패한 행 뒤에 다시 보낸다(컨슈머의 outbox-id 멱등 처리가 반복을 흡수한다)
// 따라서 한 결제의 행은 집은 순서대로 확인·표시되며, 이 보장은 모든 인스턴스가 이 폴러를 쓰고 한 결제의 앞 행이 임대로 묶여 있지 않을 때 성립한다
// 트랜잭션 없이 돈다 - 집기(임대 설정)와 PUBLISHED 표시가 각자 한 문장짜리 짧은 트랜잭션이라 확인을 기다리는 동안(Kafka 장애면 최대 40초) DB 커넥션을 쥐지 않는다
// 인스턴스가 여럿이어도 같은 행을 동시에 발행하지 않는다 - 집은 행은 lease-ms 동안 임대돼 다른 폴러가 건너뛴다
// 확인이 실패한 행과 보내지 못한 행의 임대는 바로 풀어 다음 회차가 created_at 순서로 다시 집고, 확인된 행은 같은 회차에서 PUBLISHED 가 된다
// 발행은 확인됐는데 PUBLISHED 표시가 실패하면 확인된 행의 임대는 두고 나머지만 푼다 - 표시 실패는 DB 장애라 임대를 풀면 DB 가 낫기 전까지 폴링 주기마다 같은 행을 다시 발행한다
// 그 행은 임대가 지난 뒤 다시 발행된다(at-least-once)
// 폴러가 죽어도 임대가 지난 뒤 다시 발행된다. 컨슈머가 outbox-id 헤더와 Payment 상태로 멱등 처리한다
@Slf4j
@Component
public class PaymentEventPublisher {

    private final PaymentOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> paymentEventKafkaTemplate;
    private final ConfirmStageTimers stageTimers; // 대기 · 발행 확인 구간 기록(payment.confirm.stage)

    // 구간 기록 없이 만드는 시험용 생성자
    public PaymentEventPublisher(PaymentOutboxRepository outboxRepository,
                                 KafkaTemplate<String, String> paymentEventKafkaTemplate) {
        this(outboxRepository, paymentEventKafkaTemplate, ConfirmStageTimers.noop());
    }

    @Autowired
    public PaymentEventPublisher(PaymentOutboxRepository outboxRepository,
                                 KafkaTemplate<String, String> paymentEventKafkaTemplate,
                                 ConfirmStageTimers stageTimers) {
        this.outboxRepository = outboxRepository;
        this.paymentEventKafkaTemplate = paymentEventKafkaTemplate;
        this.stageTimers = stageTimers;
    }

    // 확인 대기에 상한이 없으면 Kafka 장애 때 폴러가 영원히 멈추고 같은 스케줄러의 다른 작업도 밀린다 - 한 회차 전체의 상한이다(행마다 아님)
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
    // 기본 50ms - 승인 요청이 컨슈머 결과를 기다리는 동안 발행이 늦어지면 그만큼 응답이 늦는다
    @Scheduled(fixedDelayString = "${payment.outbox.poll-delay-ms:50}")
    public void publishPending() {
        // shortcut: 폴러가 집은 뒤 죽거나 발행 뒤 PUBLISHED 표시가 실패해 임대를 풀지 못한 행은 임대 시간만큼 기다리고 그동안 같은 결제의 더 새로운 행이 먼저 발행될 수 있다, 컨슈머가 결제 상태로 적용해 허용하며 순서가 필요한 소비자가 생기면 결제별 직렬 발행으로 바꾼다
        List<PaymentOutbox> batch = outboxRepository.claimPendingBatch(BATCH_SIZE, leaseMs);
        if (batch.isEmpty()) {
            return;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sendTimeoutMs);
        LocalDateTime claimedAt = LocalDateTime.now();
        List<CompletableFuture<?>> pending = new ArrayList<>(batch.size());
        long[] sendStartedAt = new long[batch.size()];
        for (PaymentOutbox outbox : batch) {
            // 처음 발행하는 결제 승인 행만 잰다 - 재발행 행의 createdAt 은 처음 적재한 때라 대기 시간이 부풀려진다
            if (outbox.getRepublishCount() == 0 && outbox.getTopic() == null && outbox.getCreatedAt() != null) {
                stageTimers.record(ConfirmStageTimers.OUTBOX_WAIT_CLAIM,
                        Duration.between(outbox.getCreatedAt(), claimedAt).toNanos());
            }
        }

        // 1) 확인을 기다리지 않고 한 회차를 모두 보낸다 - 프로듀서가 묶어 한꺼번에 내보낸다
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
                sendStartedAt[i] = System.nanoTime();
                pending.add(paymentEventKafkaTemplate.send(record));
            } catch (Exception e) {
                // 이 행부터는 보내지 않는다 - Kafka 장애는 건별이 아니라 브로커 전체라 남은 행도 같은 실패를 겪는다
                log.error("[OUTBOX_PUBLISH_FAILED] send 요청 실패, outboxId={}, error={}", outbox.getId(), e.getMessage());
                break;
            }
        }

        // 2) 확인을 기다린다 - 회차 전체가 상한 하나를 나눠 쓰므로 상한이 지나면 확인이 안 온 행은 바로 실패로 센다
        List<UUID> acknowledged = new ArrayList<>(pending.size());
        List<PaymentOutbox> acknowledgedRows = new ArrayList<>(pending.size());
        Set<UUID> blockedAggregates = new HashSet<>(); // 확인이 실패한 행의 결제 - 같은 결제의 뒤 행은 먼저 표시하지 않는다
        for (int i = 0; i < pending.size(); i++) {
            PaymentOutbox outbox = batch.get(i);
            try {
                pending.get(i).get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                stageTimers.record(ConfirmStageTimers.KAFKA_PUBLISH_ACK, System.nanoTime() - sendStartedAt[i]);
                // 앞 행이 실패한 결제의 뒤 행은 확인이 와도 표시하지 않고 임대를 풀어 실패한 행 뒤에 다시 보낸다 - 먼저 표시하면 컨슈머가 승인보다 취소를 먼저 본다
                if (blockedAggregates.contains(outbox.getAggregateId())) {
                    continue;
                }
                acknowledged.add(outbox.getId());
                acknowledgedRows.add(outbox);
            } catch (InterruptedException e) {
                // 인터럽트는 플래그를 복원하고 기다림을 중단한다 - 이 행부터는 확인되지 않은 것으로 센다
                Thread.currentThread().interrupt();
                log.error("[OUTBOX_PUBLISH_FAILED] interrupted, outboxId={}", outbox.getId());
                break;
            } catch (Exception e) {
                // 실패 건은 PENDING 으로 남고 임대는 아래에서 바로 푼다 (at-least-once)
                blockedAggregates.add(outbox.getAggregateId());
                log.error("[OUTBOX_PUBLISH_FAILED] outboxId={}, error={}", outbox.getId(), e.getMessage());
            }
        }

        // 3) 확인된 행을 한 문장으로 표시한다 - 인터럽트로 중단했어도 이미 확인된 행은 표시한다
        if (!acknowledged.isEmpty()) {
            try {
                outboxRepository.markPublished(acknowledged);
                for (PaymentOutbox outbox : acknowledgedRows) {
                    log.info("[OUTBOX_PUBLISHED] outboxId={}, aggregateId={}", outbox.getId(), outbox.getAggregateId());
                }
            } catch (Exception e) {
                // 이미 발행된 행이라 임대를 풀면 DB 가 낫기 전까지 매 회차 다시 발행한다 - 확인된 행의 임대는 두고 나머지만 푼다
                log.error("[OUTBOX_MARK_FAILED] 발행은 성공, 임대가 지난 뒤 다시 발행됨, count={}, error={}", acknowledged.size(), e.getMessage());
            }
        }

        // 4) 확인되지 않은 행(확인 실패 · 상한 초과 · 보내지 못함 · 인터럽트 뒤 남은 행)의 임대를 한 문장으로 푼다
        Set<UUID> acknowledgedIds = new HashSet<>(acknowledged);
        releaseLease(batch.stream().map(PaymentOutbox::getId).filter(id -> !acknowledgedIds.contains(id)).toList());
    }

    // 확인되지 않은 행의 임대를 한 문장으로 푼다. 풀지 못하면 임대 시간이 지난 뒤에 다시 집힌다
    private void releaseLease(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            outboxRepository.releaseLease(ids);
        } catch (Exception e) {
            log.error("[OUTBOX_LEASE_RELEASE_FAILED] 임대 시간이 지난 뒤 다시 집힘, count={}, error={}", ids.size(), e.getMessage());
        }
    }
}
