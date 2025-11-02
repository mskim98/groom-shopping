package groom.backend.infrastructure.security;

import groom.backend.domain.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class RedisRefreshTokenRepository implements RefreshTokenRepository {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";

    @Override
    public void save(String email, String refreshToken, long expirationMillis) {
        String key = REFRESH_TOKEN_PREFIX + email;
        redisTemplate.opsForValue().set(key, refreshToken, expirationMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<String> findByEmail(String email) {
        String key = REFRESH_TOKEN_PREFIX + email;
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    @Override
    public void deleteByEmail(String email) {
        String key = REFRESH_TOKEN_PREFIX + email;
        redisTemplate.delete(key);
    }

    @Override
    public boolean existsByEmail(String email) {
        String key = REFRESH_TOKEN_PREFIX + email;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
