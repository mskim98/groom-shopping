package groom.backend.application.payment.event;

// payment-events 레코드의 payload 를 PaymentApprovedEvent 로 읽지 못했을 때 컨슈머가 던진다
// 같은 payload 는 다시 읽어도 같은 결과라 Kafka 오류 처리기가 재시도 없이 곧장 DLQ 로 보내도록 분류하는 표지다
public class PaymentEventParseException extends RuntimeException {

    public PaymentEventParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
