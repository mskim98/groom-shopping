package groom.backend.domain.payment.repository;

import groom.backend.domain.payment.model.PaymentOutbox;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Outbox 영속화 추상화. 구현은 interfaces 계층(Spring Data JPA)에 둔다.
public interface PaymentOutboxRepository {

    PaymentOutbox save(PaymentOutbox outbox);

    // publisher 가 발행할 PENDING 이벤트를 오래된 순으로 limit 개 집으면서 leaseMs 동안 임대한다. 한 문장(짧은 트랜잭션)이라 발행하는 동안 커넥션을 쥐지 않는다
    // 임대 중이거나 다른 트랜잭션이 잠근 행은 건너뛰므로 폴러가 여럿이어도 같은 행을 동시에 집지 않는다
    List<PaymentOutbox> claimPendingBatch(int limit, long leaseMs);

    // 발행에 성공한 행을 PUBLISHED 로 표시하고 임대를 비운다. 이미 PUBLISHED 인 행은 건드리지 않는다
    void markPublished(UUID id);

    // 발행 시각이 cutoff 보다 오래된 PUBLISHED 행을 최대 limit 건 지운다(다른 트랜잭션이 잠근 행은 건너뛴다). 지운 건수를 돌려준다
    int deletePublishedBefore(LocalDateTime cutoff, int limit);

    // 결제 한 건의 해당 이벤트 행. READY 에 멈춘 결제 대사가 발행 상태를 보는 데 쓴다
    Optional<PaymentOutbox> findByAggregateIdAndEventType(UUID aggregateId, String eventType);
}
