package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

// 도메인 PaymentOutboxRepository 를 Spring Data JPA 로 구현. 도메인 ↔ JPA 엔티티 매핑 담당.
@Repository
@RequiredArgsConstructor
public class PaymentOutboxRepositoryImpl implements PaymentOutboxRepository {

    private final SpringDataPaymentOutboxRepository springRepo;

    @Override
    public PaymentOutbox save(PaymentOutbox outbox) {
        return toDomain(springRepo.save(toEntity(outbox)));
    }

    @Override
    public List<PaymentOutbox> findPendingBatch(int limit) {
        return springRepo.findByStatusOrderByCreatedAtAsc(
                        PaymentOutbox.OutboxStatus.PENDING.name(), PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
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
