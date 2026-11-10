package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// 도메인 PaymentOutboxRepository 를 Spring Data JPA 로 구현. 도메인 ↔ JPA 엔티티 매핑 담당.
@Repository
@RequiredArgsConstructor
public class PaymentOutboxRepositoryImpl implements PaymentOutboxRepository {

    // PENDING 행을 한 문장으로 집으면서 임대한다. 임대 중이 아니고 다른 트랜잭션이 잠그지 않은 행만 집으므로 폴러가 여럿이어도 같은 행을 동시에 발행하지 않는다
    // 임대(claimed_until)는 DB 시각이다 - 폴러가 죽거나 send 가 시간 초과로 끝나도 임대가 지나면 다른 회차가 다시 집는다
    // 고른 행을 MATERIALIZED CTE 로 한 번만 정한다 - UPDATE ... WHERE id IN (SELECT ... LIMIT FOR UPDATE SKIP LOCKED) 는 계획에 따라 부분 질의를 다시 평가해 LIMIT 보다 많은 행을 집는다(실측: 5 건 요청에 10 건)
    private static final String CLAIM_PENDING_SQL = """
            WITH picked AS MATERIALIZED (
                SELECT id FROM payment_outbox
                WHERE status = 'PENDING' AND (claimed_until IS NULL OR claimed_until < now())
                ORDER BY created_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            UPDATE payment_outbox o SET claimed_until = now() + (:leaseMs * interval '1 millisecond')
            FROM picked WHERE o.id = picked.id
            RETURNING o.*
            """;

    private static final String MARK_PUBLISHED_SQL = """
            UPDATE payment_outbox SET status = 'PUBLISHED', published_at = :publishedAt, claimed_until = NULL
            WHERE id = :id AND status = 'PENDING'
            """;

    private static final String RELEASE_LEASE_SQL = """
            UPDATE payment_outbox SET claimed_until = NULL
            WHERE id IN (:ids) AND status = 'PENDING'
            """;

    // 지울 행을 FOR UPDATE SKIP LOCKED 로 집는다 - 삭제 배치가 둘 동시에 돌아도 서로의 행을 기다리지 않고 각 행은 한 번만 지워진다
    private static final String DELETE_PUBLISHED_SQL = """
            WITH picked AS MATERIALIZED (
                SELECT id FROM payment_outbox
                WHERE status = 'PUBLISHED' AND published_at < :cutoff
                ORDER BY published_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            DELETE FROM payment_outbox o USING picked WHERE o.id = picked.id
            """;

    private final SpringDataPaymentOutboxRepository springRepo;
    private final EntityManager entityManager;

    @Override
    public PaymentOutbox save(PaymentOutbox outbox) {
        return toDomain(springRepo.save(toEntity(outbox)));
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public List<PaymentOutbox> claimPendingBatch(int limit, long leaseMs) {
        List<PaymentOutboxJpaEntity> rows = entityManager.createNativeQuery(CLAIM_PENDING_SQL, PaymentOutboxJpaEntity.class)
                .setParameter("leaseMs", leaseMs)
                .setParameter("limit", limit)
                .getResultList();
        // UPDATE ... RETURNING 은 순서를 보장하지 않는다 - 같은 결제의 이벤트 순서를 created_at 으로 되돌린다
        return rows.stream().map(this::toDomain).sorted(Comparator.comparing(PaymentOutbox::getCreatedAt)).toList();
    }

    @Override
    @Transactional
    public void releaseLease(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        entityManager.createNativeQuery(RELEASE_LEASE_SQL).setParameter("ids", ids).executeUpdate();
    }

    @Override
    @Transactional
    public void markPublished(UUID id) {
        entityManager.createNativeQuery(MARK_PUBLISHED_SQL)
                .setParameter("publishedAt", LocalDateTime.now())
                .setParameter("id", id)
                .executeUpdate();
    }

    @Override
    @Transactional
    public int deletePublishedBefore(LocalDateTime cutoff, int limit) {
        return entityManager.createNativeQuery(DELETE_PUBLISHED_SQL)
                .setParameter("cutoff", cutoff)
                .setParameter("limit", limit)
                .executeUpdate();
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
