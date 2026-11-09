package groom.backend.domain.payment.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CompensationStatus {
    PENDING("재시도 대기"),
    PROCESSING("배치 인스턴스가 선점해 처리 중"),
    SUCCEEDED("보상 성공"),
    FAILED("보상 실패 (재시도 대상)"),
    GIVEN_UP("재시도 한도 초과 - 수동 처리 대상"),
    NOT_APPROVED("Toss 에 승인된 결제가 없음(404) - 재시도·DLQ 없이 종료"),
    RECONCILE_NEEDED("수동 취소에서 Toss 는 취소됐는데 DB 는 DONE - 사람이 대사해야 하는 종단 상태"),
    SUPERSEDED("취소 직전 재확인에서 정상 승인(DONE)·이미 취소된 결제로 확인 - Toss 취소를 호출하지 않고 종료");

    private final String description;
}
