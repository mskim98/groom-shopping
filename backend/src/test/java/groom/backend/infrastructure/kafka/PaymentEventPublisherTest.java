package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    @Mock
    private PaymentOutboxRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> paymentEventKafkaTemplate;
    @InjectMocks
    private PaymentEventPublisher publisher;

    @Test
    void publishPending_발행_성공시_그_행을_PUBLISHED로_표시한다() {
        PaymentOutbox outbox = PaymentOutbox.create(UUID.randomUUID(), "PaymentApproved", "{}");
        given(outboxRepository.claimPendingBatch(100, 60000L)).willReturn(List.of(outbox));
        doReturn(CompletableFuture.completedFuture(null))
                .when(paymentEventKafkaTemplate).send(any(ProducerRecord.class));

        publisher.publishPending();

        verify(outboxRepository, times(1)).markPublished(outbox.getId());
    }

    @Test
    void publishPending_대기건이_없으면_아무것도_하지_않는다() {
        given(outboxRepository.claimPendingBatch(100, 60000L)).willReturn(List.of());

        publisher.publishPending();

        verify(paymentEventKafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(outboxRepository, never()).markPublished(any());
    }

    @Test
    void publishPending_첫_발행이_예외면_그_회차의_남은_행을_시도하지_않고_아무것도_PUBLISHED로_표시하지_않는다() {
        PaymentOutbox first = PaymentOutbox.create(UUID.randomUUID(), "PaymentApproved", "{}");
        PaymentOutbox second = PaymentOutbox.create(UUID.randomUUID(), "PaymentApproved", "{}");
        PaymentOutbox third = PaymentOutbox.create(UUID.randomUUID(), "PaymentApproved", "{}");
        given(outboxRepository.claimPendingBatch(100, 60000L)).willReturn(List.of(first, second, third));
        doThrow(new IllegalStateException("broker down"))
                .when(paymentEventKafkaTemplate).send(any(ProducerRecord.class));

        publisher.publishPending();

        verify(paymentEventKafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(outboxRepository, never()).markPublished(any());
    }

    @Test
    void publishPending_폴링_주기는_payment_outbox_poll_delay_ms_설정을_200ms_기본값으로_읽는다() throws Exception {
        Scheduled scheduled = PaymentEventPublisher.class.getMethod("publishPending").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${payment.outbox.poll-delay-ms:200}");
    }
}
