package groom.backend.interfaces.payment.persistence;

import groom.backend.domain.payment.model.PaymentCompensation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPaymentCompensationRepository extends JpaRepository<PaymentCompensation, UUID> {
    // 선점 조회(findRetryable)는 SKIP LOCKED 네이티브 UPDATE...RETURNING 이 필요해
    // PaymentCompensationRepositoryImpl 에서 EntityManager 로 직접 실행한다.
    // 이 인터페이스는 save·findById 용 기본 CRUD 만 남는다
}
