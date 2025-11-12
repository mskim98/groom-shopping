package groom.backend.domain.raffle.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RaffleStatus {
    DRAFT("초안"),
    READY("준비완료"),
    ACTIVE("활성"),
    CLOSED("종료"),
    DRAWN("추첨완료"),
    CANCELLED("취소");

    private final String description;
}
