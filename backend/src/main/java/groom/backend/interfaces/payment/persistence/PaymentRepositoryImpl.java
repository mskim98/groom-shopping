package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.domain.payment.repository.PaymentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

// @Repository : 영속성 계층 빈. 도메인의 PaymentRepository 인터페이스를 JPA로 '구현'한 어댑터.
// 도메인은 인터페이스만 알고, 실제 DB 접근은 Spring Data JPA에 위임한다(의존성 역전).
@Repository
// @RequiredArgsConstructor : final 필드 생성자 주입.
@RequiredArgsConstructor
public class PaymentRepositoryImpl implements PaymentRepository {

    // private final : 실제 DB 쿼리를 수행하는 Spring Data 리포지토리. 스프링이 주입.
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
