package groom.backend.domain.payment.repository;

import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByPaymentKey(String paymentKey);

    List<Payment> findByUserId(Long userId);

    List<Payment> findByUserIdAndStatus(Long userId, PaymentStatus status);

    boolean existsByOrderId(UUID orderId);

    void delete(Payment payment);

    void deleteById(UUID id);
}
