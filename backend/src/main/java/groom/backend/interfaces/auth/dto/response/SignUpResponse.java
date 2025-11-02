package groom.backend.interfaces.auth.dto.response;

import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class SignUpResponse {
    private Long id;
    private String email;
    private String name;
    private Role role;
    private Grade grade;
    private LocalDateTime createdAt;
}
