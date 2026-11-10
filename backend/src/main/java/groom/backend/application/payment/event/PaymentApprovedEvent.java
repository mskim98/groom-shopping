package groom.backend.application.payment.event;

import groom.backend.domain.product.model.enums.StockMode;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import java.util.List;
import java.util.UUID;

// Toss 승인이 끝난 결제의 후처리 요청. Outbox 에 JSON 으로 적재되어 payment-events 로 발행되고 PaymentEventConsumer 가 받는다
// tossResponse 는 후처리가 Payment 에 옮겨 적을 승인 응답 전체이고, reservedItems 는 플래시 세일 항목을 어떻게 예약했는지(REDIS | DB)를 담아
// 실패 경로가 Redis 복원이 필요한지 판단하게 한다
public record PaymentApprovedEvent(
        UUID paymentId,
        UUID orderId,
        Integer amount,
        TossPaymentResponse tossResponse,
        List<ReservedItem> reservedItems
) {
    public static final String EVENT_TYPE = "PaymentApproved";

    // 플래시 세일 주문 항목과 예약 방식. mode 가 없는 옛 payload 는 Redis 선점으로 읽는다
    public record ReservedItem(UUID productId, int quantity, StockMode mode) {
        public ReservedItem {
            if (mode == null) {
                mode = StockMode.REDIS;
            }
        }

        public ReservedItem(UUID productId, int quantity) {
            this(productId, quantity, StockMode.REDIS);
        }
    }
}
