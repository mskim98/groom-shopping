package groom.backend.interfaces.auth.dto.response;

import groom.backend.domain.auth.enums.Grade;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import groom.backend.domain.auth.enums.Role;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Schema(description = "사용자 정보 수정 응답 DTO")
public class UserUpdateResponse {
    @Schema(description = "사용자 ID", example = "1")
    private Long id;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "수정된 사용자 이름", example = "김철수")
    private String name;

    @Schema(description = "수정된 사용자 역할", example = "ROLE_ADMIN")
    private Role role;

    @Schema(description = "수정된 사용자 등급", example = "SILVER")
    private Grade grade;

    @Schema(description = "정보 수정 일시", example = "2025-11-07T11:00:00")
    private LocalDateTime updatedAt;
}