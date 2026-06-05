package groom.backend.interfaces.payment.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPaymentOutboxRepository extends JpaRepository<PaymentOutboxJpaEntity, UUID> {

    // status 가 PENDING 인 행을 created_at 오래된 순으로 가져온다 (limit 은 Pageable 로 제어).
    List<PaymentOutboxJpaEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);
}
