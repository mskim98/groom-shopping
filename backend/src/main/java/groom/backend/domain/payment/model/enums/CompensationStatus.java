package groom.backend.domain.payment.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CompensationStatus {
    PENDING("재시도 대기"),
    SUCCEEDED("보상 성공"),
    FAILED("보상 실패 (재시도 대상)"),
    GIVEN_UP("재시도 한도 초과 - 수동 처리 대상");

    private final String description;
}
