package groom.backend.domain.payment.repository;

import groom.backend.domain.payment.model.PaymentOutbox;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Outbox 영속화 추상화. 구현은 interfaces 계층(Spring Data JPA)에 둔다.
public interface PaymentOutboxRepository {

    PaymentOutbox save(PaymentOutbox outbox);

    // publisher 가 발행할 PENDING 이벤트를 오래된 순으로 limit 개 가져온다.
    List<PaymentOutbox> findPendingBatch(int limit);

    // 결제 한 건의 해당 이벤트 행. READY 에 멈춘 결제 대사가 발행 상태를 보는 데 쓴다
    Optional<PaymentOutbox> findByAggregateIdAndEventType(UUID aggregateId, String eventType);
}
