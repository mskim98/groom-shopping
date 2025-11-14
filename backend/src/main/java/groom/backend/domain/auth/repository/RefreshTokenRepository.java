package groom.backend.domain.auth.repository;

import groom.backend.domain.auth.entity.RefreshToken;

import java.util.Optional;

public interface RefreshTokenRepository {
    void save(String token, RefreshToken refreshToken, long expirationMillis);
    void addUserToken(String userTokenKey, String token, long expirationMillis);
    Optional<RefreshToken> getRefreshToken(String token);
    void deleteByToken(String token, String email);
}
