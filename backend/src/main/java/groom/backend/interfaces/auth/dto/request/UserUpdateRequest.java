package groom.backend.interfaces.auth.dto.request;

import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "사용자 정보 수정 요청 DTO")
public class UserUpdateRequest {
    @Schema(description = "수정할 등급 (예: BRONZE, SILVER, GOLD)", example = "SILVER", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Grade grade;

    @Schema(description = "수정할 역할 (예: ROLE_USER, ROLE_ADMIN)", example = "ROLE_ADMIN", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Role role;

    @Schema(description = "수정할 이름", example = "김철수", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String name;
}
