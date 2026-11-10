package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

// Outbox 폴러가 send 를 기다리는 동안 DB 커넥션을 쥐지 않고, 임대(claimed_until)로 같은 행의 동시 발행을 막는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("Outbox 폴러 임대")
class PaymentOutboxLeaseTest {

    private static final long LEASE_MS = 300;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private PaymentOutboxRepository outboxRepository;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> aggregates = new ArrayList<>();

    @AfterEach
    void tearDown() {
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

    private PaymentEventPublisher publisher(KafkaTemplate<String, String> kafka, long sendTimeoutMs) {
        return publisher(kafka, sendTimeoutMs, LEASE_MS);
    }

    private PaymentEventPublisher publisher(KafkaTemplate<String, String> kafka, long sendTimeoutMs, long leaseMs) {
        PaymentEventPublisher publisher = new PaymentEventPublisher(outboxRepository, kafka);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", sendTimeoutMs);
        ReflectionTestUtils.setField(publisher, "leaseMs", leaseMs);
        return publisher;
    }

    private String statusOf(UUID aggregate) {
        return jdbc.queryForObject("SELECT status FROM payment_outbox WHERE aggregate_id = ?", String.class, aggregate);
    }

    @Test
    @DisplayName("Kafka send 가 멈춰 있는 동안 폴러는 DB 커넥션을 쥐지 않는다")
    @SuppressWarnings("unchecked")
    void publishPending_send가_멈춰있는_동안_활성_커넥션은_0이다() throws Exception {
        seedPending(1);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CountDownLatch sending = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            return new CompletableFuture<>();
        }).when(kafka).send(any(ProducerRecord.class));
        PaymentEventPublisher publisher = publisher(kafka, 1500);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> polling = pool.submit(publisher::publishPending);
            assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);

            assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections())
                    .as("send 대기 중 활성 커넥션").isZero();
            polling.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("send 가 실패하면 실패한 행과 보내지 못한 행의 임대가 풀려 바로 created_at 순서로 다시 집힌다")
    @SuppressWarnings("unchecked")
    void publishPending_실패하면_실패한_행과_못_보낸_행의_임대를_풀어_바로_순서대로_다시_집힌다() {
        List<UUID> seeded = seedPending(3);
        UUID failing = seeded.get(0);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        List<String> sentKeys = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            sentKeys.add(record.key());
            if (failing.toString().equals(record.key())) {
                throw new IllegalStateException("broker down");
            }
            return CompletableFuture.completedFuture(null);
        }).when(kafka).send(any(ProducerRecord.class));

        publisher(kafka, 200, 60_000).publishPending();

        assertThat(sentKeys).as("첫 실패에서 회차가 끝난다").filteredOn(failing.toString()::equals).hasSize(1);
        seeded.forEach(aggregate -> assertThat(statusOf(aggregate)).isEqualTo("PENDING"));
        // 임대 60 초가 남았는데도 바로 집힌다 - 풀지 않았다면 비어 있다
        assertThat(claimedAggregatesInOrder(100, 60_000)).filteredOn(seeded::contains).containsExactlyElementsOf(seeded);
    }

    @Test
    @DisplayName("send 가 시간 초과로 끝나도 그 행과 남은 행의 임대가 풀려 바로 다시 집힌다")
    @SuppressWarnings("unchecked")
    void publishPending_send_시간초과도_임대를_풀어_바로_다시_집힌다() {
        List<UUID> seeded = seedPending(2);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        doAnswer(invocation -> new CompletableFuture<>()).when(kafka).send(any(ProducerRecord.class));

        publisher(kafka, 100, 60_000).publishPending();

        seeded.forEach(aggregate -> assertThat(statusOf(aggregate)).isEqualTo("PENDING"));
        assertThat(claimedAggregatesInOrder(100, 60_000)).filteredOn(seeded::contains).containsExactlyElementsOf(seeded);
    }

    @Test
    @DisplayName("임대를 풀지 못한 행(폴러가 집은 뒤 죽은 경우)은 임대가 지나기 전에는 집히지 않고 지난 뒤에 집힌다")
    void claimPendingBatch_풀리지_않은_임대는_지난_뒤에만_다시_집힌다() throws Exception {
        UUID stuck = seedPending(1).get(0);
        // 죽은 폴러: 집기만 하고 발행도 임대 해제도 하지 않았다
        assertThat(claimedAggregatesInOrder(100, LEASE_MS)).contains(stuck);

        assertThat(claimedAggregatesInOrder(100, LEASE_MS)).as("임대 안").doesNotContain(stuck);
        Thread.sleep(LEASE_MS + 200);
        assertThat(claimedAggregatesInOrder(100, LEASE_MS)).as("임대가 지난 뒤").contains(stuck);
    }

    // 집힌 행의 결제 id 를 집힌 순서(created_at)대로 돌려준다
    private List<UUID> claimedAggregatesInOrder(int limit, long leaseMs) {
        return outboxRepository.claimPendingBatch(limit, leaseMs).stream().map(PaymentOutbox::getAggregateId).toList();
    }

    private Set<UUID> claimedIds(int limit) {
        return new HashSet<>(claimedAggregatesInOrder(limit, LEASE_MS));
    }

    @Test
    @DisplayName("두 폴러가 동시에 집으면 서로 다른 행을 받는다")
    void claimPendingBatch_동시에_집으면_서로_다른_행을_받는다() throws Exception {
        seedPending(10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Set<UUID>> first = pool.submit(() -> {
                start.await();
                return claimedIds(5);
            });
            Future<Set<UUID>> second = pool.submit(() -> {
                start.await();
                return claimedIds(5);
            });
            start.countDown();

            Set<UUID> firstIds = first.get(15, TimeUnit.SECONDS);
            Set<UUID> secondIds = second.get(15, TimeUnit.SECONDS);

            assertThat(firstIds).hasSize(5);
            assertThat(secondIds).hasSize(5);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("발행한 행은 PUBLISHED 이고 published_at 이 채워지며 임대는 비워진다")
    @SuppressWarnings("unchecked")
    void publishPending_발행한_행은_PUBLISHED_발행시각이_있고_임대는_비워진다() {
        UUID aggregate = seedPending(1).get(0);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        doAnswer(invocation -> CompletableFuture.completedFuture(null)).when(kafka).send(any(ProducerRecord.class));

        publisher(kafka, 1000).publishPending();

        assertThat(statusOf(aggregate)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL AND claimed_until IS NULL FROM payment_outbox WHERE aggregate_id = ?",
                Boolean.class, aggregate)).isTrue();
    }
}
