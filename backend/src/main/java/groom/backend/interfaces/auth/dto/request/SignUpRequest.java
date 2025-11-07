package groom.backend.interfaces.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "회원가입 요청 DTO")
public class SignUpRequest {
    @Schema(description = "사용자 이메일", example = "newuser@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Email
    private String email;

    @Schema(description = "사용자 비밀번호", example = "securePass123!", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String password;

    @Schema(description = "사용자 이름", example = "홍길동", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String name;

    // TODO : 개발 편의를 위해 임시 추가 (추후 삭제 예정)
    @Schema(description = """
            사용자 역할 (기본값: ROLE_USER)
            개발 편의를 위해 임시 추가 (추후 삭제 예정)
            """, example = "ROLE_USER", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Role role = Role.ROLE_USER;


    @Schema(description = "사용자 등급 (기본값: BRONZE)", example = "BRONZE", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Grade grade  = Grade.BRONZE;

}
