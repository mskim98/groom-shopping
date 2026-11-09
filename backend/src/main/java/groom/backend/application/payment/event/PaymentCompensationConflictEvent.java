package groom.backend.application.payment.event;

import java.time.LocalDateTime;
import java.util.UUID;

// 결제에 이미 재시도 불가 종단 상태(SUCCEEDED · GIVEN_UP · RECONCILE_NEEDED)의 보상 행이 있는데 새 보상 의도가 온 이상 징후.
// 행은 건드리지 않고 이 이벤트를 DLQ 토픽으로 보내 운영자가 보게 한다
public record PaymentCompensationConflictEvent(
        UUID paymentId,
        String existingStatus,
        String existingReason,
        String newPaymentKey,
        String newReason,
        LocalDateTime occurredAt
) {
    public static final String EVENT_TYPE = "COMPENSATION_CONFLICT_ON_TERMINAL_ROW";
}
