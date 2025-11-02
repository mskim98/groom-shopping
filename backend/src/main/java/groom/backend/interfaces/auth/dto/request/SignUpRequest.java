package groom.backend.interfaces.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SignUpRequest {
    @NotBlank
    @Email
    private String email;
    @NotBlank
    private String password;
    @NotBlank
    private String name;

    // TODO : 개발 편의를 위해 임시 추가 (추후 삭제 예정)
    private Role role = Role.ROLE_USER;
    private Grade grade  = Grade.BRONZE;

}
