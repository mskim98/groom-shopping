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
    private Role role = Role.ROLE_USER;
    private Grade grade  = Grade.BRONZE;

}
