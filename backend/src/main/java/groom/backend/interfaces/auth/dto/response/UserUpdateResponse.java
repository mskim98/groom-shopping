package groom.backend.interfaces.auth.dto.response;

import groom.backend.domain.auth.enums.Grade;
import lombok.AllArgsConstructor;
import lombok.Getter;
import groom.backend.domain.auth.enums.Role;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class UserUpdateResponse {
    private Long id;
    private String email;
    private String name;
    private Role role;
    private Grade grade;
    private LocalDateTime updatedAt;
}
