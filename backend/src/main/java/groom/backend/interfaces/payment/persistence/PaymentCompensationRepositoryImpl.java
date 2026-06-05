package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentCompensationRepositoryImpl implements PaymentCompensationRepository {

    private final SpringDataPaymentCompensationRepository jpa;

    @Override
    public PaymentCompensation save(PaymentCompensation compensation) {
        return jpa.save(compensation);
    }

    @Override
    public Optional<PaymentCompensation> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public List<PaymentCompensation> findRetryable(LocalDateTime now, int limit) {
        return jpa.findRetryable(
                List.of(CompensationStatus.PENDING, CompensationStatus.FAILED),
                now,
                PageRequest.of(0, limit));
    }
}
