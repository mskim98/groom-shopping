package groom.backend.domain.payment.model;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 결제 이벤트를 DB에 먼저 적재해 두는 Outbox 도메인 모델.
// 결제 후처리 트랜잭션과 같은 커밋으로 저장되어, 커밋 성공 = 이벤트 보존이 된다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOutbox {

    private UUID id;
    private UUID aggregateId;        // 결제(Payment) ID
    private String eventType;        // 예: PAYMENT_COMPLETED
    private String payload;          // 이벤트 JSON
    private OutboxStatus status;     // PENDING / PUBLISHED
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    public enum OutboxStatus { PENDING, PUBLISHED }

    // 신규 적재용 팩토리. 처음엔 항상 PENDING 상태.
    public static PaymentOutbox create(UUID aggregateId, String eventType, String payload) {
        PaymentOutbox outbox = new PaymentOutbox();
        outbox.id = UUID.randomUUID();
        outbox.aggregateId = aggregateId;
        outbox.eventType = eventType;
        outbox.payload = payload;
        outbox.status = OutboxStatus.PENDING;
        outbox.createdAt = LocalDateTime.now();
        return outbox;
    }

    // 영속 데이터 복원용 (Repository 매핑에서 사용)
    public static PaymentOutbox of(UUID id, UUID aggregateId, String eventType, String payload,
            OutboxStatus status, LocalDateTime createdAt, LocalDateTime publishedAt) {
        PaymentOutbox outbox = new PaymentOutbox();
        outbox.id = id;
        outbox.aggregateId = aggregateId;
        outbox.eventType = eventType;
        outbox.payload = payload;
        outbox.status = status;
        outbox.createdAt = createdAt;
        outbox.publishedAt = publishedAt;
        return outbox;
    }

    // Kafka 발행 성공 시 호출 — PUBLISHED 로 전이하고 발행 시각을 남긴다.
    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }
}
