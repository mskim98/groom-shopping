package groom.backend.application.auth.service;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.RefreshToken;
import groom.backend.domain.auth.enums.Role;
import groom.backend.infrastructure.security.JwtRsaTokenProvider;
import groom.backend.infrastructure.security.RedisRefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class RedisRefreshTokenService {


    private final JwtRsaTokenProvider jwtTokenProvider;
    private final RedisRefreshTokenRepository redisRefreshTokenRepository;

    public RedisRefreshTokenService(JwtRsaTokenProvider jwtTokenProvider, RedisRefreshTokenRepository redisRefreshTokenRepository) {
        this.redisRefreshTokenRepository = redisRefreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Refresh Token 저장
     */
    public void saveRefreshToken(String email, String token, String userAgent, String ipAddress) {
        String tokenId = UUID.randomUUID().toString();

        // RefreshToken 엔티티 생성
        RefreshToken refreshToken = RefreshToken.builder()
                .id(tokenId)
                .email(email)
                .token(token)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusSeconds(jwtTokenProvider.getRefreshTokenValidityInMilliseconds() / 1000))
                .userAgent(userAgent)
                .ipAddress(ipAddress)
                .build();

        // Redis에 저장
        redisRefreshTokenRepository.save(token, refreshToken, jwtTokenProvider.getRefreshTokenValidityInMilliseconds());

        // 사용자별 토큰 집합에 토큰 추가
        redisRefreshTokenRepository.addUserToken(email , token, jwtTokenProvider.getRefreshTokenValidityInMilliseconds());

        log.info("Refresh token saved: username={}, tokenId={}", email, tokenId);
    }

    /**
     * Refresh Token 검증
     */
    public boolean validateRefreshToken(String token) {

        if (!jwtTokenProvider.validateToken(token)) {
            return false;
        }

        if (!jwtTokenProvider.isRefreshToken(token)) {
            return false;
        }

        Optional<RefreshToken> refreshToken = getRefreshToken(token);
        if (refreshToken.isEmpty()) {
            log.warn("Refresh token not found in Redis: token={}", token);
            return false;
        }

        if (refreshToken.get().getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("Refresh token expired: token={}", refreshToken);
            String email = refreshToken.get().getEmail();
            deleteByToken(token, email);

            return false;
        }

        return true;
    }

    /**
     * Refresh Token 삭제 (로그아웃)
     */
    public void deleteRefreshToken(String token) {
        Optional<RefreshToken> refreshToken = getRefreshToken(token);

        if (refreshToken.isPresent()) {
            String email = refreshToken.get().getEmail();

            deleteByToken(token, email);

            log.info("Refresh token deleted: email={}, token={}", email, token);
        }
    }

    /**
     * Refresh Token Rotation (재발급)
     */
    public String rotateRefreshToken(String oldToken, String userAgent, String ipAddress) {
        Optional<RefreshToken> oldRefreshToken = getRefreshToken(oldToken);

        if (oldRefreshToken.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        String email = oldRefreshToken.get().getEmail();
        Role role = jwtTokenProvider.getRole(oldToken);

        // 1. 기존 토큰 삭제
        deleteByToken(oldToken, email);

        // 2. 새 토큰 생성
        String newToken = jwtTokenProvider.createRefreshToken(email, role);

        // 3. 새 토큰 저장
        saveRefreshToken(email, newToken, userAgent, ipAddress);

        log.info("Refresh token rotated: email={}", email);

        return newToken;
    }

    /**
     * Refresh Token 삭제 by token
     */
    public void deleteByToken(String token, String email) {
        redisRefreshTokenRepository.deleteByToken(token, email);
    }

    /**
     * Refresh Token 조회
     */
    public Optional<RefreshToken> getRefreshToken(String token) {
        return redisRefreshTokenRepository.getRefreshToken(token);
    }
}
