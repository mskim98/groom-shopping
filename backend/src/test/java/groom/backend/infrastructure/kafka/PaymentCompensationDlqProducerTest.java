package groom.backend.infrastructure.kafka;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentCompensationDlqEvent;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentCompensationDlqProducerTest {

    @Mock
    private KafkaTemplate<String, String> paymentEventKafkaTemplate;

    @Test
    void publish_이벤트를_JSON으로_직렬화해_DLQ_토픽으로_전송한다() {
        // LocalDateTime(JSR-310) 직렬화를 위해 모듈 등록 (운영에선 Spring 이 구성한 ObjectMapper 사용)
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PaymentCompensationDlqProducer producer =
                new PaymentCompensationDlqProducer(paymentEventKafkaTemplate, objectMapper);

        PaymentCompensationDlqEvent event = new PaymentCompensationDlqEvent(
                UUID.randomUUID(), UUID.randomUUID(), "pk-1", 1000, "사유", "마지막 에러", 5, LocalDateTime.now());

        producer.publish(event);

        // 토픽·키(paymentKey) 확인 + payload 에 paymentKey 가 직렬화되어 포함되는지 확인
        verify(paymentEventKafkaTemplate, times(1))
                .send(eq("payment-compensation-dlq"), eq("pk-1"), contains("pk-1"));
    }
}
