package groom.backend.interfaces.auth.dto.response;

import groom.backend.domain.auth.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "로그인 응답 DTO")
public class LoginResponse {
    @Schema(description = "액세스 토큰 (Access Token)", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbkB0ZXN0LmNvbSIsInJvbGUiOiJST0xFX0FETUlOIiwiaWF0IjoxNzYyMDUyMTUyLCJleHAiOjE3NjIwNTM5NTJ9.H3gY7729hxWCkAQEMIh8EHrEhNI4xzzX-pwxYoCnQ4o")
    private String accessToken;

    @Schema(description = "사용자 이름", example = "홍길동")
    private String name;

    @Schema(description = "사용자 역할", example = "ROLE_USER")
    private Role role;
}