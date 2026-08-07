package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
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

@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    @Mock
    private PaymentOutboxRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> paymentEventKafkaTemplate;
    @InjectMocks
    private PaymentEventPublisher publisher;

    @Test
    void publishPending_발행_성공시_PUBLISHED로_전이하고_저장한다() {
        PaymentOutbox outbox = PaymentOutbox.create(UUID.randomUUID(), "PAYMENT_COMPLETED", "{}");
        given(outboxRepository.findPendingBatch(100)).willReturn(List.of(outbox));
        doReturn(CompletableFuture.completedFuture(null))
                .when(paymentEventKafkaTemplate).send(any(ProducerRecord.class));

        publisher.publishPending();

        assertThat(outbox.getStatus()).isEqualTo(PaymentOutbox.OutboxStatus.PUBLISHED);
        verify(outboxRepository, times(1)).save(outbox);
    }

    @Test
    void publishPending_대기건이_없으면_아무것도_하지_않는다() {
        given(outboxRepository.findPendingBatch(100)).willReturn(List.of());

        publisher.publishPending();

        verify(paymentEventKafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(outboxRepository, never()).save(any());
    }
}
