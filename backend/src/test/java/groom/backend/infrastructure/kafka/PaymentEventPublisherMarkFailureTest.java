package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import groom.backend.application.payment.ConfirmStageTimers;
import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;

// 스프링 컨텍스트 없이 본다 - 같은 컨텍스트의 실제 폴러(200ms 주기)가 공유 DB 의 PENDING 행을 집어 가는 일과 무관하다
// 임대 SQL 자체는 PaymentOutboxLeaseTest 가 본다. 여기서는 폴러가 임대를 어떤 행에 풀라고 요청하는지를 본다
@ExtendWith(MockitoExtension.class)
@DisplayName("Outbox 폴러 PUBLISHED 표시 실패")
class PaymentEventPublisherMarkFailureTest {

    @Mock
    private PaymentOutboxRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> paymentEventKafkaTemplate;
    // 발행기가 생성자로 받는 확정 구간 기록기 - 비워 두면 @InjectMocks 가 null 을 넣어 발행 회차가 NPE 로 멈춘다
    @Mock
    private ConfirmStageTimers stageTimers;
    @InjectMocks
    private PaymentEventPublisher publisher;

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted(); // 인터럽트 시험이 남긴 플래그가 다음 시험으로 번지지 않게 한다
    }

    private static PaymentOutbox row() {
        return PaymentOutbox.create(UUID.randomUUID(), "PaymentApproved", "{}");
    }

    @Test
    @DisplayName("확인된 행의 PUBLISHED 표시가 던지면 그 행들의 임대는 풀지 않고 확인되지 않은 행만 풀며 임대가 남은 행은 다음 회차에 집히지 않아 다시 보내지 않는다")
    @SuppressWarnings("unchecked")
    void publishPending_표시가_실패하면_확인된_행의_임대는_유지하고_확인되지_않은_행만_풀고_다음_회차에_다시_보내지_않는다() {
        PaymentOutbox marked = row();
        PaymentOutbox alsoMarked = row();
        PaymentOutbox unacknowledged = row();
        // 두 번째 회차에는 임대가 남은 확인된 행이 빠진 나머지만 집힌다 (집기 SQL 이 임대 중인 행을 건너뛴다)
        given(outboxRepository.claimPendingBatch(100, 60000L))
                .willReturn(List.of(marked, alsoMarked, unacknowledged))
                .willReturn(List.of(unacknowledged));
        doThrow(new DataAccessResourceFailureException("db down")).when(outboxRepository)
                .markPublished(List.of(marked.getId(), alsoMarked.getId()));
        List<String> sentKeys = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            sentKeys.add(record.key());
            if (record.key().equals(unacknowledged.getAggregateId().toString())) {
                return CompletableFuture.failedFuture(new IllegalStateException("record too large"));
            }
            return CompletableFuture.completedFuture(null);
        }).when(paymentEventKafkaTemplate).send(any(ProducerRecord.class));

        publisher.publishPending();

        // 확인된 두 행은 임대를 남기고 확인되지 않은 행만 푼다
        verify(outboxRepository, times(1)).releaseLease(List.of(unacknowledged.getId()));

        publisher.publishPending();

        assertThat(sentKeys.stream().filter(marked.getAggregateId().toString()::equals))
                .as("임대 안에 같은 행을 다시 보낸 횟수").hasSize(1);
        assertThat(sentKeys.stream().filter(unacknowledged.getAggregateId().toString()::equals))
                .as("임대를 푼 행은 다음 회차에 다시 보낸다").hasSize(2);
        verify(outboxRepository, never()).releaseLease(List.of(marked.getId(), alsoMarked.getId(), unacknowledged.getId()));
    }

    @Test
    @DisplayName("마지막 행의 표시가 던지면 풀 행이 없으므로 임대 해제를 요청하지 않는다")
    @SuppressWarnings("unchecked")
    void publishPending_마지막_행의_표시가_실패하면_임대_해제를_요청하지_않는다() {
        PaymentOutbox only = row();
        given(outboxRepository.claimPendingBatch(100, 60000L)).willReturn(List.of(only));
        doThrow(new DataAccessResourceFailureException("db down")).when(outboxRepository).markPublished(List.of(only.getId()));
        doAnswer(invocation -> CompletableFuture.completedFuture(null))
                .when(paymentEventKafkaTemplate).send(any(ProducerRecord.class));

        publisher.publishPending();

        verify(outboxRepository, never()).releaseLease(any());
    }

    @Test
    @DisplayName("send 중 인터럽트가 오면 그 행과 못 보낸 나머지의 임대를 풀고 스레드의 인터럽트 플래그를 남긴다")
    @SuppressWarnings("unchecked")
    void publishPending_인터럽트면_그_행과_나머지의_임대를_풀고_인터럽트_플래그를_남긴다() throws Exception {
        PaymentOutbox first = row();
        PaymentOutbox second = row();
        given(outboxRepository.claimPendingBatch(100, 60000L)).willReturn(List.of(first, second));
        CompletableFuture<Object> interrupted = mock(CompletableFuture.class);
        when(interrupted.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("stop"));
        doAnswer(invocation -> interrupted).when(paymentEventKafkaTemplate).send(any(ProducerRecord.class));

        publisher.publishPending();
        boolean flagSet = Thread.interrupted();

        assertThat(flagSet).as("스레드의 인터럽트 플래그").isTrue();
        verify(outboxRepository, times(1)).releaseLease(List.of(first.getId(), second.getId()));
        verify(outboxRepository, never()).markPublished(any());
    }
}
