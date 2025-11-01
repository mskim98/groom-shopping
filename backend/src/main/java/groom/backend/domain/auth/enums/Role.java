package groom.backend.domain.auth.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {
    ROLE_USER("사용자"),
    ROLE_ADMIN("관리자");

    private final String description;
}
