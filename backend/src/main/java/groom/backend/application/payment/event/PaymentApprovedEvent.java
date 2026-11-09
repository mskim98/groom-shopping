package groom.backend.application.payment.event;

import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import java.util.List;
import java.util.UUID;

// Toss 승인이 끝난 결제의 후처리 요청. Outbox 에 JSON 으로 적재되어 payment-events 로 발행되고 PaymentEventConsumer 가 받는다
// tossResponse 는 후처리가 Payment 에 옮겨 적을 승인 응답 전체이고, reservedItems 는 실패 때 Redis 에서 되돌릴 선점 항목이다
public record PaymentApprovedEvent(
        UUID paymentId,
        UUID orderId,
        Integer amount,
        TossPaymentResponse tossResponse,
        List<ReservedItem> reservedItems
) {
    public static final String EVENT_TYPE = "PaymentApproved";

    // Redis 에 실제로 선점된 주문 항목(미초기화 상품은 선점되지 않아 담기지 않는다)
    public record ReservedItem(UUID productId, int quantity) {
    }
}
