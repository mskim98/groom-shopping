package groom.backend.domain.auth.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {
    private String id;              // Redis Key로 사용
    private String email;
    private String token;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private String userAgent;
    private String ipAddress;
}
