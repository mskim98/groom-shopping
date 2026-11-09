package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.PaymentApplicationService;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.payment.event.PaymentEventParseException;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private PaymentApplicationService paymentApplicationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentEventConsumer(paymentApplicationService, objectMapper);
    }

    @Test
    void consume_PaymentApproved_레코드면_서비스_후처리를_이벤트_그대로_호출한다() throws Exception {
        // 중복 처리 판정은 서비스가 Payment 상태로 하므로 컨슈머는 파싱한 이벤트를 그대로 넘긴다
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        PaymentApprovedEvent event = new PaymentApprovedEvent(
                paymentId, orderId, 1000, new TossPaymentResponse(),
                List.of(new PaymentApprovedEvent.ReservedItem(productId, 2)));
        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("payment-events", 0, 0L, paymentId.toString(), json);
        record.headers().add("outbox-id", "ob-1".getBytes(StandardCharsets.UTF_8));

        consumer.consume(record);

        ArgumentCaptor<PaymentApprovedEvent> captor = ArgumentCaptor.forClass(PaymentApprovedEvent.class);
        verify(paymentApplicationService).applyApprovedEvent(captor.capture());
        PaymentApprovedEvent passed = captor.getValue();
        assertThat(passed.paymentId()).isEqualTo(paymentId);
        assertThat(passed.orderId()).isEqualTo(orderId);
        assertThat(passed.amount()).isEqualTo(1000);
        assertThat(passed.reservedItems())
                .containsExactly(new PaymentApprovedEvent.ReservedItem(productId, 2));
    }

    @Test
    void consume_읽을_수_없는_JSON이면_PaymentEventParseException을_던지고_서비스를_부르지_않는다() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("payment-events", 0, 0L, "key", "not-json");

        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(PaymentEventParseException.class);
        verify(paymentApplicationService, never()).applyApprovedEvent(any());
    }

    @Test
    void consume_서비스가_예외를_던지면_그대로_전파한다() throws Exception {
        // 재시도와 DLQ 전송은 Kafka 오류 처리기(DefaultErrorHandler)가 맡으므로 컨슈머는 예외를 삼키지 않는다
        PaymentApprovedEvent event = new PaymentApprovedEvent(
                UUID.randomUUID(), UUID.randomUUID(), 1000, new TossPaymentResponse(), List.of());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "payment-events", 0, 0L, "key", objectMapper.writeValueAsString(event));
        doThrow(new IllegalStateException("db down")).when(paymentApplicationService).applyApprovedEvent(any());

        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(IllegalStateException.class);
    }
}
