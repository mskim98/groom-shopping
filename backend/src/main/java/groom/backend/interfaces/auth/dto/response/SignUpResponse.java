package groom.backend.interfaces.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SignUpResponse {
    private Long id;
    private String email;
    private String name;
}
