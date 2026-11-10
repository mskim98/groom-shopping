package groom.backend.interfaces.payment.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPaymentOutboxRepository extends JpaRepository<PaymentOutboxJpaEntity, UUID> {

    // 결제 한 건의 해당 이벤트 행(결제당 1건이 정상이지만 여럿이면 가장 최근 것)
    Optional<PaymentOutboxJpaEntity> findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(UUID aggregateId, String eventType);
}
