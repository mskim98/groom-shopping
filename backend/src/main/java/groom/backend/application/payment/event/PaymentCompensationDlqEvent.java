package groom.backend.application.payment.event;

import java.time.LocalDateTime;
import java.util.UUID;

// 결제 보상(환불)이 재시도 한도를 넘겨 최종 실패(GIVEN_UP)했을 때 DLQ 로 보내는 이벤트.
// 운영팀이 즉시 인지하고 수동 처리할 수 있도록 핵심 정보를 담는다.
public record PaymentCompensationDlqEvent(
        UUID compensationId,
        UUID paymentId,
        String paymentKey,
        Integer amount,
        String reason,
        String lastError,
        Integer retryCount,
        LocalDateTime occurredAt
) {}
