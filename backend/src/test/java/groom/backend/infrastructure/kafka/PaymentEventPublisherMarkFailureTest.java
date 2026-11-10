package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

// 발행은 성공했는데 PUBLISHED 표시가 실패한 행의 임대는 유지하고(임대가 끝나야 다시 발행), 인터럽트는 임대를 풀고 플래그를 남기는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("Outbox 폴러 PUBLISHED 표시 실패")
class PaymentEventPublisherMarkFailureTest {

    private static final long LEASE_MS = 60_000;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private PaymentOutboxRepository outboxRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> aggregates = new ArrayList<>();

    @AfterEach
    void tearDown() {
        Thread.interrupted(); // 인터럽트 시험이 남긴 플래그가 정리 쿼리와 다음 시험으로 번지지 않게 한다
        for (UUID aggregate : aggregates) {
            jdbc.update("DELETE FROM payment_outbox WHERE aggregate_id = ?", aggregate);
        }
    }

    // 다른 시험이 남긴 PENDING 행보다 앞에 오도록 오래된 created_at 으로 적재한다
    private List<UUID> seedPending(int count) {
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID aggregate = UUID.randomUUID();
            aggregates.add(aggregate);
            seeded.add(aggregate);
            PaymentOutbox outbox = outboxRepository.save(PaymentOutbox.create(aggregate, "PaymentApproved", "{}"));
            jdbc.update("UPDATE payment_outbox SET created_at = ? WHERE id = ?",
                    LocalDateTime.of(2000, 1, 1, 0, 0).plusSeconds(i), outbox.getId());
        }
        return seeded;
    }

    private PaymentEventPublisher publisher(KafkaTemplate<String, String> kafka, PaymentOutboxRepository repository) {
        PaymentEventPublisher publisher = new PaymentEventPublisher(repository, kafka);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1000L);
        ReflectionTestUtils.setField(publisher, "leaseMs", LEASE_MS);
        return publisher;
    }

    private UUID outboxIdOf(UUID aggregate) {
        return jdbc.queryForObject("SELECT id FROM payment_outbox WHERE aggregate_id = ?", UUID.class, aggregate);
    }

    private boolean leased(UUID aggregate) {
        return jdbc.queryForObject("SELECT COALESCE(claimed_until > now(), false) FROM payment_outbox WHERE aggregate_id = ?",
                Boolean.class, aggregate);
    }

    private boolean notLeased(UUID aggregate) {
        return jdbc.queryForObject("SELECT claimed_until IS NULL FROM payment_outbox WHERE aggregate_id = ?",
                Boolean.class, aggregate);
    }

    private String statusOf(UUID aggregate) {
        return jdbc.queryForObject("SELECT status FROM payment_outbox WHERE aggregate_id = ?", String.class, aggregate);
    }

    @Test
    @DisplayName("발행은 성공했는데 PUBLISHED 표시가 던지면 그 행의 임대는 남기고 못 보낸 행의 임대는 풀며 다음 회차는 그 행을 다시 보내지 않는다")
    @SuppressWarnings("unchecked")
    void publishPending_표시가_실패하면_그_행의_임대는_유지하고_뒤의_행만_풀고_다음_회차에_다시_보내지_않는다() {
        List<UUID> seeded = seedPending(3);
        UUID marked = seeded.get(0);
        PaymentOutboxRepository repository = mock(PaymentOutboxRepository.class, delegatesTo(outboxRepository));
        doThrow(new DataAccessResourceFailureException("db down")).when(repository).markPublished(outboxIdOf(marked));
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        List<String> sentKeys = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            sentKeys.add(record.key());
            return CompletableFuture.completedFuture(null);
        }).when(kafka).send(any(ProducerRecord.class));
        PaymentEventPublisher publisher = publisher(kafka, repository);

        publisher.publishPending();

        assertThat(sentKeys).as("첫 행만 보내고 표시 실패에서 회차를 끝낸다").containsExactly(marked.toString());
        assertThat(statusOf(marked)).isEqualTo("PENDING");
        assertThat(leased(marked)).as("표시에 실패한 행의 임대").isTrue();
        assertThat(notLeased(seeded.get(1))).as("보내지 못한 둘째 행의 임대").isTrue();
        assertThat(notLeased(seeded.get(2))).as("보내지 못한 셋째 행의 임대").isTrue();

        publisher.publishPending();

        assertThat(sentKeys.stream().filter(marked.toString()::equals)).as("임대 안에 같은 행을 다시 보낸 횟수").hasSize(1);
        assertThat(sentKeys).contains(seeded.get(1).toString(), seeded.get(2).toString());
    }

    @Test
    @DisplayName("send 중 인터럽트가 오면 그 행과 못 보낸 나머지의 임대를 풀고 스레드의 인터럽트 플래그를 남긴다")
    @SuppressWarnings("unchecked")
    void publishPending_인터럽트면_그_행과_나머지의_임대를_풀고_인터럽트_플래그를_남긴다() throws Exception {
        List<UUID> seeded = seedPending(2);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<Object> interrupted = mock(CompletableFuture.class);
        when(interrupted.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("stop"));
        doAnswer(invocation -> interrupted).when(kafka).send(any(ProducerRecord.class));

        publisher(kafka, outboxRepository).publishPending();
        boolean flagSet = Thread.interrupted();

        assertThat(flagSet).as("스레드의 인터럽트 플래그").isTrue();
        assertThat(notLeased(seeded.get(0))).as("인터럽트된 행의 임대").isTrue();
        assertThat(notLeased(seeded.get(1))).as("보내지 못한 행의 임대").isTrue();
        assertThat(statusOf(seeded.get(0))).isEqualTo("PENDING");
    }
}
