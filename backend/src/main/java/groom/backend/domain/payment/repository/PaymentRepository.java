package groom.backend.domain.payment.repository;

import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 도메인 계층의 저장소 '인터페이스'(추상화). 구현 기술(JPA 등)에 의존하지 않게 하여
// 도메인이 인프라를 모르도록 분리한다. 실제 구현은 interfaces 계층의 PaymentRepositoryImpl 이 담당한다.
public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByPaymentKey(String paymentKey);

    List<Payment> findByUserId(Long userId);

    List<Payment> findByUserIdAndStatus(Long userId, PaymentStatus status);

    boolean existsByOrderId(UUID orderId);

    // READY 인데 updated_at 이 cutoff 보다 오래된 결제를 오래된 순으로 limit 건 집는다. READY 에 멈춘 결제 대사용
    // 집는 즉시 updated_at 을 갱신하므로 같은 결제를 여러 인스턴스가 동시에 집지 않는다(다음 stale 기준 시간 동안 다시 나오지 않는다)
    List<Payment> findStaleReady(LocalDateTime cutoff, int limit);

    void delete(Payment payment);

    void deleteById(UUID id);
}
