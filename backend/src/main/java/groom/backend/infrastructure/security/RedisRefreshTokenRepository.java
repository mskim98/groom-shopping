package groom.backend.infrastructure.security;

import groom.backend.domain.auth.entity.RefreshToken;
import groom.backend.domain.auth.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
public class RedisRefreshTokenRepository implements RefreshTokenRepository {


    private final RedisTemplate<String, RefreshToken> tokenRedisTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";
    private static final String USER_TOKENS_PREFIX = "user_tokens:";

    public RedisRefreshTokenRepository(@Qualifier("tokenRedisTemplate") RedisTemplate<String, RefreshToken> tokenRedisTemplate,
                                       @Qualifier("stringRedisTemplate") RedisTemplate<String, String> redisTemplate) {
        this.tokenRedisTemplate = tokenRedisTemplate;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(String token, RefreshToken refreshToken, long expirationMillis) {
        String key = REFRESH_TOKEN_PREFIX + token;
        tokenRedisTemplate.opsForValue().set(
                key,
                refreshToken,
                expirationMillis,
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void addUserToken(String email, String token, long expirationMillis) {
        String key = USER_TOKENS_PREFIX + email;
        redisTemplate.opsForSet().add(key, token);
        redisTemplate.expire(key, expirationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Refresh Token 조회
     */
    @Override
    public Optional<RefreshToken> getRefreshToken(String token) {
        String key = REFRESH_TOKEN_PREFIX + token;
        RefreshToken refreshToken = tokenRedisTemplate.opsForValue().get(key);
        return Optional.ofNullable(refreshToken);
    }

    /**
     * 토큰 삭제
     */
    @Override
    public void deleteByToken(String token, String email) {
        String tokenKey = REFRESH_TOKEN_PREFIX + token;
        redisTemplate.delete(tokenKey);

        String userTokenKey = USER_TOKENS_PREFIX + email;
        redisTemplate.opsForSet().remove(userTokenKey, token);
    }
}
