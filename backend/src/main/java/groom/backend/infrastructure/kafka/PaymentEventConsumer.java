package groom.backend.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.PaymentApplicationService;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// payment-events 토픽의 PaymentApproved 레코드(Toss 승인이 끝난 결제)를 받아 후처리를 맡기는 컨슈머.
// 후처리(Payment DONE · Order CONFIRMED · 재고 차감 · 티켓 발급)와 실패 처리는 PaymentApplicationService.applyApprovedEvent 가 하고,
// 같은 레코드가 다시 와도 Payment 가 READY 가 아니면 건너뛰므로 멱등이다(별도 처리 표시를 두지 않는다)
// 비즈니스 실패는 서비스가 보상·복원·FAILED 로 처리하고 정상 반환한다. 결제를 읽지 못하는 DB 장애와 파싱 실패만 던져
// 컨테이너의 DefaultErrorHandler 가 재시도한 뒤 payment-events-dlq 로 보낸다
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentApplicationService paymentApplicationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "payment-events",
            groupId = "payment-event-group",
            containerFactory = "paymentApprovedKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        PaymentApprovedEvent event;
        try {
            event = objectMapper.readValue(record.value(), PaymentApprovedEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("PaymentApproved 레코드를 읽지 못했다: key=" + record.key(), e);
        }
        log.info("[PAYMENT_EVENT_CONSUMED] key={}, paymentId={}", record.key(), event.paymentId());
        paymentApplicationService.applyApprovedEvent(event);
    }
}
