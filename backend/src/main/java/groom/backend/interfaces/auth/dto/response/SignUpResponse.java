package groom.backend.interfaces.auth.dto.response;

import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Schema(description = "회원가입 응답 DTO")
public class SignUpResponse {
    @Schema(description = "사용자 ID", example = "1")
    private Long id;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "사용자 이름", example = "홍길동")
    private String name;

    @Schema(description = "사용자 역할", example = "ROLE_USER")
    private Role role;

    @Schema(description = "사용자 등급", example = "BRONZE")
    private Grade grade;

    @Schema(description = "계정 생성 일시", example = "2025-11-02T11:53:57.878105")
    private LocalDateTime createdAt;
}