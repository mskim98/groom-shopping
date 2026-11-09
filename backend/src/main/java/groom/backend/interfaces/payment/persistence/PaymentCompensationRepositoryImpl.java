package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import jakarta.persistence.EntityManager;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentCompensationRepositoryImpl implements PaymentCompensationRepository {

    // 선점 조회를 원자적 단일 문장으로 묶는 SQL. SELECT 로 후보를 고르는 서브쿼리에
    // FOR UPDATE SKIP LOCKED 를 걸어 다른 인스턴스가 잠근 행을 건너뛰고,
    // 그 결과를 바로 UPDATE...RETURNING 으로 커밋해 조회와 선점 사이에 틈이 없다
    private static final String ACQUIRE_RETRYABLE_SQL = """
            UPDATE payment_compensation
            SET status = 'PROCESSING', updated_at = now()
            WHERE id IN (
                SELECT id
                FROM payment_compensation
                WHERE (status IN ('PENDING', 'FAILED') AND next_retry_at <= :now)
                   OR (status = 'PROCESSING' AND updated_at <= :staleThreshold
                       AND COALESCE(next_retry_at, :now) <= :now)
                ORDER BY next_retry_at ASC NULLS LAST
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """;

    private final SpringDataPaymentCompensationRepository jpa;
    private final EntityManager entityManager;

    @Override
    public PaymentCompensation save(PaymentCompensation compensation) {
        return jpa.save(compensation);
    }

    @Override
    public Optional<PaymentCompensation> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PaymentCompensation> findRetryable(LocalDateTime now, LocalDateTime staleThreshold, int limit) {
        return entityManager.createNativeQuery(ACQUIRE_RETRYABLE_SQL, PaymentCompensation.class)
                .setParameter("now", now)
                .setParameter("staleThreshold", staleThreshold)
                .setParameter("limit", limit)
                .getResultList();
    }

    // 적재 계수용 - 선점 SQL 과 같은 WHERE 이나 LIMIT·FOR UPDATE 없이 총량만 센다
    private static final String COUNT_RETRYABLE_SQL = """
            SELECT count(*) FROM payment_compensation
            WHERE (status IN ('PENDING', 'FAILED') AND next_retry_at <= :now)
               OR (status = 'PROCESSING' AND updated_at <= :staleThreshold
                   AND COALESCE(next_retry_at, :now) <= :now)
            """;

    @Override
    public long countRetryable(LocalDateTime now, LocalDateTime staleThreshold) {
        Object result = entityManager.createNativeQuery(COUNT_RETRYABLE_SQL)
                .setParameter("now", now)
                .setParameter("staleThreshold", staleThreshold)
                .getSingleResult();
        return ((Number) result).longValue();
    }

    @Override
    public long countByStatus(CompensationStatus status) {
        return jpa.countByStatus(status);
    }
}
