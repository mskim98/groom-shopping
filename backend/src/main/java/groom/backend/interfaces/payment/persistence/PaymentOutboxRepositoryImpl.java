package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

// 도메인 PaymentOutboxRepository 를 Spring Data JPA 로 구현. 도메인 ↔ JPA 엔티티 매핑 담당.
@Repository
@RequiredArgsConstructor
public class PaymentOutboxRepositoryImpl implements PaymentOutboxRepository {

    // PENDING 행을 집으면서 잠근다. 다른 인스턴스(트랜잭션)가 잠근 행은 건너뛰므로 폴러가 여럿이어도 같은 행을 발행하지 않는다
    // 잠금은 호출한 트랜잭션이 끝날 때까지 이어지므로, 폴러는 발행과 PUBLISHED 표시를 같은 트랜잭션에서 한다
    private static final String CLAIM_PENDING_SQL = """
            SELECT * FROM payment_outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private final SpringDataPaymentOutboxRepository springRepo;
    private final EntityManager entityManager;

    @Override
    public PaymentOutbox save(PaymentOutbox outbox) {
        return toDomain(springRepo.save(toEntity(outbox)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PaymentOutbox> findPendingBatch(int limit) {
        List<PaymentOutboxJpaEntity> rows = entityManager.createNativeQuery(CLAIM_PENDING_SQL, PaymentOutboxJpaEntity.class)
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<PaymentOutbox> findByAggregateIdAndEventType(UUID aggregateId, String eventType) {
        return springRepo.findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(aggregateId, eventType)
                .map(this::toDomain);
    }

    private PaymentOutboxJpaEntity toEntity(PaymentOutbox o) {
        return PaymentOutboxJpaEntity.builder()
                .id(o.getId())
                .aggregateId(o.getAggregateId())
                .eventType(o.getEventType())
                .payload(o.getPayload())
                .topic(o.getTopic())
                .status(o.getStatus().name())
                .createdAt(o.getCreatedAt())
                .publishedAt(o.getPublishedAt())
                .republishCount(o.getRepublishCount())
                .build();
    }

    private PaymentOutbox toDomain(PaymentOutboxJpaEntity e) {
        return PaymentOutbox.of(
                e.getId(),
                e.getAggregateId(),
                e.getEventType(),
                e.getPayload(),
                e.getTopic(),
                PaymentOutbox.OutboxStatus.valueOf(e.getStatus()),
                e.getCreatedAt(),
                e.getPublishedAt(),
                e.getRepublishCount());
    }
}
