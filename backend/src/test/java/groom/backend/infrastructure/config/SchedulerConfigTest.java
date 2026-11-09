package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.infrastructure.kafka.PaymentEventPublisher;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        // 가상 스레드 설정에서 Boot 가 SimpleAsyncTaskScheduler 를 만드는 상황을 그대로 재현한다
        "spring.threads.virtual.enabled=true"})
@DisplayName("스케줄러 설정(T3-8)")
class SchedulerConfigTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private TaskScheduler taskScheduler;

    @Test
    @DisplayName("TaskScheduler 는 풀 크기 4 이상의 ThreadPoolTaskScheduler 이고 작업 4개가 동시에 돈다")
    void TaskScheduler_ThreadPoolTaskScheduler이고_풀크기가_4이상이다() throws InterruptedException {
        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler pool = (ThreadPoolTaskScheduler) taskScheduler;
        assertThat(pool.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(4);

        // 서로를 기다리는 작업 4개 - 스레드 1개를 공유하면 래치가 풀리지 않아 시간 초과로 끝난다
        CountDownLatch allRunning = new CountDownLatch(4);
        ConcurrentHashMap<String, Boolean> threads = new ConcurrentHashMap<>();
        for (int i = 0; i < 4; i++) {
            pool.schedule(() -> {
                threads.put(Thread.currentThread().getName(), true);
                allRunning.countDown();
                try {
                    allRunning.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, Instant.now());
        }
        assertThat(allRunning.await(5, TimeUnit.SECONDS)).as("동시에 실행된 작업 수 4").isTrue();
        assertThat(threads.keySet()).hasSize(4).allMatch(name -> name.startsWith("scheduled-"));
    }

    @Test
    @DisplayName("Outbox 발행의 Kafka send 가 끝나지 않으면 타임아웃으로 폴러가 돌아온다")
    @SuppressWarnings("unchecked")
    void 아웃박스발행_send가_멈추면_타임아웃으로_끝난다() {
        PaymentOutboxRepository outboxRepository = mock(PaymentOutboxRepository.class);
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        PaymentOutbox outbox = PaymentOutbox.create(UUID.randomUUID(), "PAYMENT_COMPLETED", "{}");
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(outbox));
        // 브로커가 응답하지 않는 상황 - 완료되지 않는 Future
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        PaymentEventPublisher publisher = new PaymentEventPublisher(outboxRepository, kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 200L);

        assertTimeoutPreemptively(Duration.ofSeconds(3), publisher::publishPending);

        verify(outboxRepository, never()).save(any());
        assertThat(outbox.getStatus()).as("발행 못 한 행은 PENDING 으로 남아 다음 주기에 재시도").isEqualTo(PaymentOutbox.OutboxStatus.PENDING);
    }
}
