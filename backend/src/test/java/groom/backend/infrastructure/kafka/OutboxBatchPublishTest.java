package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import groom.backend.application.payment.ConfirmStageTimers;
import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.infrastructure.config.KafkaConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

// 폴러가 한 회차의 행을 모두 보낸 뒤 확인을 한 번에 기다리고, 확인된 행을 한 문장으로 PUBLISHED 표시하는지 본다
// 스프링 컨텍스트 없이 본다 - 같은 컨텍스트의 실제 폴러가 공유 DB 의 PENDING 행을 집어 가는 일과 무관하다
@DisplayName("Outbox 폴러 배치 발행")
class OutboxBatchPublishTest {

    private static final long ACK_DELAY_MS = 100;

    private final ScheduledExecutorService acks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outbox-test-ack");
        t.setDaemon(true);
        return t;
    });
    private final PaymentOutboxRepository outboxRepository = mock(PaymentOutboxRepository.class);
    private final ConfirmStageTimers stageTimers = mock(ConfirmStageTimers.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final List<ProducerRecord<String, String>> sent = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void tearDown() {
        Thread.interrupted(); // 인터럽트 시험이 남긴 플래그가 다음 시험으로 번지지 않게 한다
        acks.shutdownNow();
    }

    private static PaymentOutbox row() {
        return PaymentOutbox.create(UUID.randomUUID(), "PaymentApproved", "{}");
    }

    private static List<PaymentOutbox> rows(int count) {
        List<PaymentOutbox> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row());
        }
        return rows;
    }

    private static List<UUID> ids(PaymentOutbox... rows) {
        return java.util.Arrays.stream(rows).map(PaymentOutbox::getId).toList();
    }

    // 보낸 때로부터 ACK_DELAY_MS 뒤에 확인되는 send - failIndexes 의 번째 send 는 그때 실패로 끝난다
    @SuppressWarnings("unchecked")
    private void stubSend(int... failIndexes) {
        java.util.Set<Integer> failing = new HashSet<>();
        for (int index : failIndexes) {
            failing.add(index);
        }
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            int index = sent.size();
            sent.add(invocation.getArgument(0));
            CompletableFuture<Object> future = new CompletableFuture<>();
            acks.schedule(() -> {
                if (failing.contains(index)) {
                    future.completeExceptionally(new IllegalStateException("record too large"));
                } else {
                    future.complete(null);
                }
            }, ACK_DELAY_MS, TimeUnit.MILLISECONDS);
            return future;
        });
    }

    private PaymentEventPublisher publisher(long sendTimeoutMs) {
        PaymentEventPublisher publisher = new PaymentEventPublisher(outboxRepository, kafka, stageTimers);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", sendTimeoutMs);
        return publisher;
    }

    @Test
    @DisplayName("확인이 100ms 뒤에 오는 행 50건을 1초 안에 모두 보내고 PUBLISHED 표시를 한 번의 호출로 한다")
    void publishPending_행50건이면_1초_안에_끝나고_표시는_한_번에_한다() {
        List<PaymentOutbox> batch = rows(50);
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(batch);
        stubSend();

        long startedAt = System.nanoTime();
        publisher(40000).publishPending();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMs).as("행을 하나씩 보내고 기다리면 5초 이상").isLessThan(1000);
        assertThat(sent).hasSize(50);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> marked = ArgumentCaptor.forClass(List.class);
        verify(outboxRepository, times(1)).markPublished(marked.capture());
        assertThat(marked.getValue()).containsExactlyInAnyOrderElementsOf(batch.stream().map(PaymentOutbox::getId).toList());
        verify(outboxRepository, never()).releaseLease(anyList());
    }

    @Test
    @DisplayName("대기 구간은 처음 발행하는 행마다 한 번, 발행 확인 구간은 확인된 행마다 보낸 뒤 확인까지의 시간으로 기록한다")
    void publishPending_구간_기록기의_의미를_유지한다() {
        PaymentOutbox first = row();
        PaymentOutbox second = row();
        PaymentOutbox republished = PaymentOutbox.of(UUID.randomUUID(), UUID.randomUUID(), "PaymentApproved", "{}", null,
                PaymentOutbox.OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(5), null, 1);
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(List.of(first, second, republished));
        stubSend();

        publisher(40000).publishPending();

        verify(stageTimers, times(2)).record(eq(ConfirmStageTimers.OUTBOX_WAIT_CLAIM), anyLong());
        ArgumentCaptor<Long> ackNanos = ArgumentCaptor.forClass(Long.class);
        verify(stageTimers, times(3)).record(eq(ConfirmStageTimers.KAFKA_PUBLISH_ACK), ackNanos.capture());
        assertThat(ackNanos.getAllValues()).allMatch(nanos -> nanos >= TimeUnit.MILLISECONDS.toNanos(ACK_DELAY_MS - 10));
    }

    @Test
    @DisplayName("확인이 실패한 행은 PENDING 으로 두고 임대만 풀며 같은 회차에서 확인된 행은 PUBLISHED 로 표시한다")
    void publishPending_확인이_실패한_행만_임대를_풀고_나머지는_표시한다() {
        PaymentOutbox first = row();
        PaymentOutbox failing = row();
        PaymentOutbox third = row();
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(List.of(first, failing, third));
        stubSend(1);

        publisher(40000).publishPending();

        verify(outboxRepository, times(1)).markPublished(ids(first, third));
        verify(outboxRepository, times(1)).releaseLease(ids(failing));
        verify(stageTimers, times(2)).record(eq(ConfirmStageTimers.KAFKA_PUBLISH_ACK), anyLong());
    }

    @Test
    @DisplayName("PUBLISHED 표시가 던지면 확인된 행의 임대는 두어 다음 회차에 다시 보내지 않고 확인되지 않은 행의 임대만 푼다")
    void publishPending_표시가_실패하면_확인된_행의_임대는_두고_나머지만_푼다() {
        PaymentOutbox first = row();
        PaymentOutbox second = row();
        PaymentOutbox failing = row();
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(List.of(first, second, failing));
        stubSend(2);
        doThrow(new DataAccessResourceFailureException("db down")).when(outboxRepository).markPublished(anyList());

        publisher(40000).publishPending();

        verify(outboxRepository, times(1)).markPublished(ids(first, second));
        verify(outboxRepository, times(1)).releaseLease(ids(failing));
        verify(outboxRepository, never()).releaseLease(ids(first, second, failing));
    }

    @Test
    @DisplayName("확인된 행이 모두 표시에 실패하고 풀 행이 없으면 임대 해제를 요청하지 않는다")
    void publishPending_모두_확인됐는데_표시가_실패하면_임대_해제를_요청하지_않는다() {
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(rows(3));
        stubSend();
        doThrow(new DataAccessResourceFailureException("db down")).when(outboxRepository).markPublished(anyList());

        publisher(40000).publishPending();

        verify(outboxRepository, never()).releaseLease(anyList());
    }

    @Test
    @DisplayName("확인을 기다리다 인터럽트가 오면 확인되지 않은 행의 임대를 풀고 확인된 행은 표시하며 인터럽트 플래그를 남긴다")
    @SuppressWarnings("unchecked")
    void publishPending_인터럽트면_확인되지_않은_행의_임대를_풀고_플래그를_남긴다() throws Exception {
        PaymentOutbox first = row();
        PaymentOutbox second = row();
        PaymentOutbox third = row();
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(List.of(first, second, third));
        CompletableFuture<Object> interrupted = mock(CompletableFuture.class);
        when(interrupted.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("stop"));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn((CompletableFuture) CompletableFuture.completedFuture(null))
                .thenReturn((CompletableFuture) interrupted);

        publisher(40000).publishPending();
        boolean flagSet = Thread.interrupted();

        assertThat(flagSet).as("스레드의 인터럽트 플래그").isTrue();
        verify(outboxRepository, times(1)).markPublished(ids(first));
        verify(outboxRepository, times(1)).releaseLease(ids(second, third));
    }

    @Test
    @DisplayName("같은 결제의 두 행은 집은 순서대로 같은 키로 보낸다")
    void publishPending_같은_결제의_두_행은_집은_순서대로_같은_키로_보낸다() {
        UUID payment = UUID.randomUUID();
        PaymentOutbox approved = PaymentOutbox.create(payment, "PaymentApproved", "{\"n\":1}");
        PaymentOutbox cancelled = PaymentOutbox.create(payment, "PaymentCancelled", "{\"n\":2}");
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(List.of(approved, cancelled));
        stubSend();

        publisher(40000).publishPending();

        assertThat(sent).extracting(ProducerRecord::key).containsExactly(payment.toString(), payment.toString());
        assertThat(sent).extracting(ProducerRecord::value).containsExactly("{\"n\":1}", "{\"n\":2}");
    }

    @Test
    @DisplayName("한 결제의 앞 행 확인이 실패하면 같은 결제의 뒤 행은 확인이 와도 표시하지 않고 임대를 풀며 다른 결제의 행은 표시한다")
    void publishPending_앞_행이_실패하면_같은_결제의_뒤_행은_표시하지_않고_임대를_푼다() {
        UUID paymentA = UUID.randomUUID();
        UUID paymentB = UUID.randomUUID();
        PaymentOutbox a1 = PaymentOutbox.create(paymentA, "PaymentApproved", "{}");
        PaymentOutbox b1 = PaymentOutbox.create(paymentB, "PaymentApproved", "{}");
        PaymentOutbox a2 = PaymentOutbox.create(paymentA, "PaymentCancelled", "{}");
        PaymentOutbox b2 = PaymentOutbox.create(paymentB, "PaymentCancelled", "{}");
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(List.of(a1, b1, a2, b2));
        stubSend(0);

        publisher(40000).publishPending();

        verify(outboxRepository, times(1)).markPublished(ids(b1, b2));
        verify(outboxRepository, times(1)).releaseLease(ids(a1, a2));
    }

    @Test
    @DisplayName("send 가 던지면 그 행부터 보내지 않고 이미 보낸 행의 확인은 기다려 표시하며 나머지 행의 임대를 푼다")
    void publishPending_send가_던지면_보낸_행은_표시하고_못_보낸_행의_임대를_푼다() {
        PaymentOutbox first = row();
        PaymentOutbox second = row();
        PaymentOutbox third = row();
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(List.of(first, second, third));
        CompletableFuture<Object> ok = CompletableFuture.completedFuture(null);
        when(kafka.send(any(ProducerRecord.class)))
                .thenAnswer(invocation -> ok)
                .thenThrow(new IllegalStateException("broker down"));

        publisher(40000).publishPending();

        verify(kafka, times(2)).send(any(ProducerRecord.class));
        verify(outboxRepository, times(1)).markPublished(ids(first));
        verify(outboxRepository, times(1)).releaseLease(ids(second, third));
    }

    @Test
    @DisplayName("확인이 오지 않으면 회차 전체가 보내기 상한 한 번만 기다리고 모든 행의 임대를 푼다")
    void publishPending_확인이_오지_않으면_상한_한_번만_기다리고_임대를_푼다() {
        PaymentOutbox first = row();
        PaymentOutbox second = row();
        PaymentOutbox third = row();
        when(outboxRepository.claimPendingBatch(100, 60000L)).thenReturn(List.of(first, second, third));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> new CompletableFuture<>());

        long startedAt = System.nanoTime();
        publisher(500).publishPending();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMs).as("행마다 500ms 를 기다리면 1.5초 이상").isLessThan(1200);
        verify(outboxRepository, never()).markPublished(anyList());
        verify(outboxRepository, times(1)).releaseLease(ids(first, second, third));
    }

    @Test
    @DisplayName("payment-events 프로듀서는 멱등 전송과 in-flight 5 이하를 명시해 한 결제의 행이 보낸 순서를 지킨다")
    void paymentEventProducerFactory_멱등_전송과_in_flight_5_이하를_명시한다() {
        KafkaConfig config = new KafkaConfig(new SimpleMeterRegistry());
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");

        DefaultKafkaProducerFactory<String, String> factory =
                (DefaultKafkaProducerFactory<String, String>) config.paymentEventProducerFactory();

        assertThat(factory.getConfigurationProperties())
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all");
        assertThat(((Number) factory.getConfigurationProperties().get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)).intValue())
                .isBetween(1, 5);
    }
}
