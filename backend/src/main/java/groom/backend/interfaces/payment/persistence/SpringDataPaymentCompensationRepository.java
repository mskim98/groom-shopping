package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataPaymentCompensationRepository extends JpaRepository<PaymentCompensation, UUID> {

    @Query("""
            SELECT c FROM PaymentCompensation c
            WHERE c.status IN :statuses
              AND c.nextRetryAt <= :now
            ORDER BY c.nextRetryAt ASC
            """)
    List<PaymentCompensation> findRetryable(
            @Param("statuses") List<CompensationStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable);
}
