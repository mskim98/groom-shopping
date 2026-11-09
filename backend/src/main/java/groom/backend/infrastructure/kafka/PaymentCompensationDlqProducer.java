package groom.backend.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentCompensationDlqEvent;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 보상 최종 실패(GIVEN_UP) 이벤트를 payment-compensation-dlq 토픽으로 발행한다.
// 7.1 에서 만든 String-String KafkaTemplate 을 재사용(payload 는 JSON 문자열).
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompensationDlqProducer {

    private final KafkaTemplate<String, String> paymentEventKafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String TOPIC = PaymentCompensationDlqEvent.TOPIC;

    public void publish(PaymentCompensationDlqEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            // key = paymentKey → 같은 결제 건의 DLQ 이벤트는 같은 파티션(순서 보장)
            // 결과를 기다리되 상한을 둔다 - 상한 없는 get() 은 Kafka 장애 때 호출 스레드를 붙잡는다
            paymentEventKafkaTemplate.send(TOPIC, event.paymentKey(), payload).get(40, TimeUnit.SECONDS);
            log.warn("[COMPENSATION_DLQ_PUBLISHED] compensationId={}, paymentKey={}",
                    event.compensationId(), event.paymentKey());
        } catch (Exception e) {
            // DLQ 발행 실패해도 보상 흐름 자체를 막지 않도록 로깅만 (GIVEN_UP 기록은 DB 에 남아 있음)
            log.error("[COMPENSATION_DLQ_PUBLISH_FAILED] compensationId={}, error={}",
                    event.compensationId(), e.getMessage());
        }
    }
}
