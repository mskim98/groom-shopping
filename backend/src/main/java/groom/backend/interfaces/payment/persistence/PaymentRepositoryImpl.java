package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.domain.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// @Repository : 영속성 계층 빈. 도메인의 PaymentRepository 인터페이스를 JPA로 '구현'한 어댑터.
// 도메인은 인터페이스만 알고, 실제 DB 접근은 Spring Data JPA에 위임한다(의존성 역전).
@Repository
// @RequiredArgsConstructor : final 필드 생성자 주입.
@RequiredArgsConstructor
public class PaymentRepositoryImpl implements PaymentRepository {

    // private final : 실제 DB 쿼리를 수행하는 Spring Data 리포지토리. 스프링이 주입.
    private final SpringDataPaymentRepository springDataPaymentRepository;
    private final EntityManager entityManager;

    // READY 대사의 선점. FOR UPDATE SKIP LOCKED 로 집으면서 같은 문장에서 updated_at 을 지금으로 올려, 다른 인스턴스와 다음 회차가
    // 이 결제를 stale 기준(5분) 동안 다시 집지 못하게 한다. 대사가 REQUIRES_NEW 로 같은 행을 쓰므로 잠금을 대사 끝까지 쥐지 않는다
    private static final String CLAIM_STALE_READY_SQL = """
            UPDATE payment SET updated_at = now()
            WHERE id IN (
                SELECT id FROM payment
                WHERE status = 'READY' AND updated_at < :cutoff
                ORDER BY updated_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """;

    @Override
    public Payment save(Payment payment) {
        return springDataPaymentRepository.save(payment);
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return springDataPaymentRepository.findById(id);
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
        return springDataPaymentRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<Payment> findByPaymentKey(String paymentKey) {
        return springDataPaymentRepository.findByPaymentKeyValue(paymentKey);
    }

    @Override
    public List<Payment> findByUserId(Long userId) {
        return springDataPaymentRepository.findByUserId(userId);
    }

    @Override
    public List<Payment> findByUserIdAndStatus(Long userId, PaymentStatus status) {
        return springDataPaymentRepository.findByUserIdAndStatus(userId, status);
    }

    @Override
    public boolean existsByOrderId(UUID orderId) {
        return springDataPaymentRepository.existsByOrderId(orderId);
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public List<Payment> findStaleReady(LocalDateTime cutoff, int limit) {
        return entityManager.createNativeQuery(CLAIM_STALE_READY_SQL, Payment.class)
                .setParameter("cutoff", cutoff)
                .setParameter("limit", limit)
                .getResultList();
    }

    @Override
    public void delete(Payment payment) {
        springDataPaymentRepository.delete(payment);
    }

    @Override
    public void deleteById(UUID id) {
        springDataPaymentRepository.deleteById(id);
    }
}
