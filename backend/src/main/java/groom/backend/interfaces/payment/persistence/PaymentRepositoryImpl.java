package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.domain.payment.repository.PaymentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentRepositoryImpl implements PaymentRepository {

    private final SpringDataPaymentRepository springDataPaymentRepository;

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
    public void delete(Payment payment) {
        springDataPaymentRepository.delete(payment);
    }

    @Override
    public void deleteById(UUID id) {
        springDataPaymentRepository.deleteById(id);
    }
}
