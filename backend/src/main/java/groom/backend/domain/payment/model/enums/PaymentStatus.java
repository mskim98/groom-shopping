package groom.backend.domain.payment.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
    PENDING("결제 대기"),
    READY("결제 준비"),
    IN_PROGRESS("결제 진행중"),
    WAITING_FOR_DEPOSIT("입금 대기"),
    DONE("결제 완료"),
    CANCELED("결제 취소"),
    PARTIAL_CANCELED("부분 취소"),
    ABORTED("결제 중단"),
    EXPIRED("결제 만료"),
    FAILED("결제 실패");

    private final String description;
}
