package groom.backend.domain.auth.repository;

import java.util.Optional;

public interface RefreshTokenRepository {
    void save(String email, String refreshToken, long expirationMillis);
    Optional<String> findByEmail(String email);
    void deleteByEmail(String email);
    boolean existsByEmail(String email);
}
