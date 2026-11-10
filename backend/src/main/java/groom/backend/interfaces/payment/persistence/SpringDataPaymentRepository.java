package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataPaymentRepository extends JpaRepository<Payment, UUID> {

    @Query("SELECT p FROM Payment p WHERE p.order.id = :orderId")
    Optional<Payment> findByOrderId(@Param("orderId") UUID orderId);

    @Query("SELECT p FROM Payment p WHERE p.paymentKey.value = :paymentKey")
    Optional<Payment> findByPaymentKeyValue(@Param("paymentKey") String paymentKey);

    List<Payment> findByUserId(Long userId);

    @Query("SELECT p FROM Payment p WHERE p.userId = :userId AND p.status = :status")
    List<Payment> findByUserIdAndStatus(@Param("userId") Long userId, @Param("status") PaymentStatus status);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Payment p WHERE p.order.id = :orderId")
    boolean existsByOrderId(@Param("orderId") UUID orderId);
}
