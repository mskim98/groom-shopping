package groom.backend.application.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "blacklist:";


    private final RedisTemplate<String, String> redisTemplate;

    @Value("${jwt.access.expiration}")
    private long accessTokenExpiration;

    public TokenBlacklistService(@Qualifier("stringRedisTemplate") RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Access Token을 블랙리스트에 추가 (로그아웃 시)
     */
    public void blacklistToken(String token) {
        String key = BLACKLIST_PREFIX + token;
        redisTemplate.opsForValue().set(
                key,
                "true",
                accessTokenExpiration,
                TimeUnit.MILLISECONDS
        );
        log.info("Token blacklisted");
    }

    /**
     * 블랙리스트 확인
     */
    public boolean isBlacklisted(String token) {
        String key = BLACKLIST_PREFIX + token;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
