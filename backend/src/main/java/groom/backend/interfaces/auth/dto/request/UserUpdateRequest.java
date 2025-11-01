package groom.backend.interfaces.auth.dto.request;

import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserUpdateRequest {
    private Grade grade;
    private Role role;
    private String name;
}
