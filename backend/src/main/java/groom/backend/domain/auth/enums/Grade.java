package groom.backend.domain.auth.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 회원 등급 enum
 * - code: DB에 저장될 코드 값
 * - displayName: 클라이언트용 표시 이름
 */
@Getter
@RequiredArgsConstructor
public enum Grade {
    BRONZE("BR", "Bronze"),
    SILVER("SI", "Silver"),
    GOLD("GD", "Gold"),
    PLATINUM("PL", "Platinum"),
    VIP("VP", "VIP");

    private final String code;
    private final String displayName;
}
