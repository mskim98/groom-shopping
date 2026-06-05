package groom.backend.application.payment.event;

import java.time.LocalDateTime;
import java.util.UUID;

// 결제 완료 이벤트 페이로드. Outbox 에 JSON 으로 적재되어 Kafka 로 발행된다.
// 다운스트림(알림·정산·통계)이 이 이벤트를 구독한다.
public record PaymentCompletedEvent(
        UUID paymentId,
        UUID orderId,
        Integer amount,
        LocalDateTime occurredAt
) {
    public static final String EVENT_TYPE = "PAYMENT_COMPLETED";
}
