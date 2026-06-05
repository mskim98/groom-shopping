package groom.backend.domain.payment.repository;

import groom.backend.domain.payment.model.PaymentOutbox;
import java.util.List;

// Outbox 영속화 추상화. 구현은 interfaces 계층(Spring Data JPA)에 둔다.
public interface PaymentOutboxRepository {

    PaymentOutbox save(PaymentOutbox outbox);

    // publisher 가 발행할 PENDING 이벤트를 오래된 순으로 limit 개 가져온다.
    List<PaymentOutbox> findPendingBatch(int limit);
}
