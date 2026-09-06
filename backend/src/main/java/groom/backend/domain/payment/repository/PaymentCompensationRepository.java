package groom.backend.domain.payment.repository;

import groom.backend.domain.payment.model.PaymentCompensation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCompensationRepository {

    PaymentCompensation save(PaymentCompensation compensation);

    Optional<PaymentCompensation> findById(UUID id);

    // 선점 조회. 아래 두 조건 중 하나를 만족하는 행을 최대 limit 개까지 골라 그 자리에서
    // PROCESSING 으로 갱신·커밋하고 갱신된 행을 반환한다(SELECT 와 UPDATE 가 분리돼 있으면
    // 그 틈에 다른 인스턴스가 같은 행을 또 고를 수 있어 원자적으로 묶는다)
    // ① status in (PENDING, FAILED) AND nextRetryAt <= now — 정상 재시도 대상
    // ② status = PROCESSING AND updatedAt <= staleThreshold — 선점한 인스턴스가 죽어 회수 대상
    List<PaymentCompensation> findRetryable(LocalDateTime now, LocalDateTime staleThreshold, int limit);
}
