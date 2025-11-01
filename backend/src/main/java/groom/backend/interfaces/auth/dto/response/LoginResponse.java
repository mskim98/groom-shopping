package groom.backend.interfaces.auth.dto.response;

import groom.backend.domain.auth.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private String name;
    private Role role;
}
