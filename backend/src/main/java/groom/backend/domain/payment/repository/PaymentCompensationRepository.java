package groom.backend.domain.payment.repository;

import groom.backend.domain.payment.model.PaymentCompensation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCompensationRepository {

    PaymentCompensation save(PaymentCompensation compensation);

    Optional<PaymentCompensation> findById(UUID id);

    // status in (PENDING, FAILED) AND nextRetryAt <= now 인 건을 최대 limit 만큼 조회
    List<PaymentCompensation> findRetryable(LocalDateTime now, int limit);
}
