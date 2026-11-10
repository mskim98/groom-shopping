package groom.backend.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.PaymentApplicationService;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.payment.event.PaymentEventParseException;
import groom.backend.infrastructure.resilience.DbLedgerCircuit;
import groom.backend.infrastructure.resilience.DbLedgerOpenException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// payment-events 토픽의 PaymentApproved 레코드(Toss 승인이 끝난 결제)를 받아 후처리를 맡기는 컨슈머.
// 후처리(Payment DONE · Order CONFIRMED · 일반 상품 재고 차감 · 티켓 발급)와 실패 처리는 PaymentApplicationService.applyApprovedEvent 가 하고,
// 같은 레코드가 다시 와도 Payment 가 READY 가 아니면 건너뛰므로 멱등이다(별도 처리 표시를 두지 않는다)
// 비즈니스 실패는 서비스가 보상·복원·FAILED 로 처리하고 정상 반환한다. 결제를 읽지 못하는 DB 장애는 던져 컨테이너의
// DefaultErrorHandler 가 재시도한 뒤 payment-events-dlq 로 보내고, 파싱 실패(PaymentEventParseException)는 재시도 없이 곧장 DLQ 로 간다
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentApplicationService paymentApplicationService;
    private final ObjectMapper objectMapper;
    // 필수 의존성 - 빠지면 기동이 실패해야 한다(선택 주입이면 db-ledger 보호가 조용히 꺼진다)
    private final DbLedgerCircuit dbLedgerCircuit;

    @KafkaListener(
            id = DbLedgerCircuit.LISTENER_ID,
            topics = "payment-events",
            groupId = "payment-event-group",
            containerFactory = "paymentApprovedKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        PaymentApprovedEvent event;
        try {
            event = objectMapper.readValue(record.value(), PaymentApprovedEvent.class);
        } catch (JsonProcessingException e) {
            throw new PaymentEventParseException("PaymentApproved 레코드를 읽지 못했다: key=" + record.key(), e);
        }
        log.info("[PAYMENT_EVENT_CONSUMED] key={}, paymentId={}", record.key(), event.paymentId());
        try {
            dbLedgerCircuit.run(() -> paymentApplicationService.applyApprovedEvent(event));
        } catch (CallNotPermittedException e) {
            // 회로가 열려 있다 - 승인하지 않고 되감아 풀린 뒤 다시 읽는다(재시도 횟수 · DLQ 로 세지 않는다)
            throw new DbLedgerOpenException();
        }
    }
}
