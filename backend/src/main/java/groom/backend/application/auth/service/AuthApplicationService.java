package groom.backend.application.auth.service;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.repository.UserRepository;
import groom.backend.infrastructure.security.CookieUtil;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.infrastructure.security.JwtRsaTokenProvider;
import groom.backend.interfaces.auth.dto.request.LoginRequest;
import groom.backend.interfaces.auth.dto.request.SignUpRequest;
import groom.backend.interfaces.auth.dto.request.UserUpdateRequest;
import groom.backend.interfaces.auth.dto.response.LoginResponse;
import groom.backend.interfaces.auth.dto.response.SignUpResponse;
import groom.backend.interfaces.auth.dto.response.TokenRefreshResponse;
import groom.backend.interfaces.auth.dto.response.UserUpdateResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthApplicationService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtRsaTokenProvider jwtTokenProvider;
    private final CookieUtil cookieUtil;
    private final RedisRefreshTokenService refreshTokenService;

    private final TokenBlacklistService tokenBlacklistService;

    @Transactional
    public SignUpResponse register(SignUpRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        if (userRepo.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATION);
        }

        // TODO : 개발 편의를 위해 ROLE, GRADE 요청대로 적용 (추후 삭제 예정)
        User user = User.create(email,
                                passwordEncoder.encode(request.getPassword()),
                                request.getName(),
                                request.getRole(),
                                request.getGrade());

        User saved = userRepo.save(user);

        return new SignUpResponse(saved.getId(), saved.getEmail(), saved.getName(),
                 saved.getRole(), saved.getGrade(), saved.getCreatedAt());

    }

    @Transactional
    public LoginResponse login(LoginRequest loginRequest, HttpServletRequest request, HttpServletResponse response) {

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getPassword())
            );

            Object principal = authentication.getPrincipal();
            User user = ((CustomUserDetails) principal).getUser();

            // Access Token 생성
            String accessToken = jwtTokenProvider.createAccessToken(user.getEmail(), user.getRole());

            // Refresh Token 생성
            String refreshToken = jwtTokenProvider.createRefreshToken(user.getEmail(), user.getRole());

            // Refresh Token 저장(Redis)
            String userAgent = request.getHeader("User-Agent");
            String ipAddress = getClientIp(request);
            refreshTokenService.saveRefreshToken(user.getEmail(), refreshToken, userAgent, ipAddress);

            // Refresh Token을 HttpOnly Cookie로 설정
            Cookie refreshTokenCookie = createRefreshTokenCookie(refreshToken);
            response.addCookie(refreshTokenCookie);

            log.info("로그인 성공: {}", user.getEmail());

            return new LoginResponse(accessToken, user.getName(), user.getRole());
        } catch (BadCredentialsException e) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
    }

    @Transactional
    public TokenRefreshResponse refreshToken(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookieUtil.getRefreshTokenFromCookie(request);

        // 1. Refresh Token 존재 여부 확인
        if (refreshToken == null) {
            throw new BusinessException(ErrorCode.NO_REFRESH_TOKEN);
        }

        // 2. Refresh Token 유효성 검사
        if(!refreshTokenService.validateRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 3. Refresh Token에서 사용자 정보 추출
        String email = jwtTokenProvider.getEmail(refreshToken);

        // 4. 사용자 존재 여부 확인
        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 5. 새로운 Access Token 생성
        String newAccessToken = jwtTokenProvider.createAccessToken(user.getEmail(), user.getRole());

        // 7. Refresh Token Rotation
        String userAgent = request.getHeader("User-Agent");
        String ipAddress = getClientIp(request);
        String newRefreshToken = refreshTokenService.rotateRefreshToken(refreshToken, userAgent, ipAddress);

        // 8. 새로운 Refresh Token을 HttpOnly Cookie로 설정
        Cookie newRefreshTokenCookie = createRefreshTokenCookie(newRefreshToken);
        response.addCookie(newRefreshTokenCookie);

        log.info("액세스 토큰 재발급: {}", email);

        return new TokenRefreshResponse(newAccessToken);
    }

    @Transactional
    public UserUpdateResponse updateUser(Long id, UserUpdateRequest request) {
        User user = userRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        user.updateUser(request.getName(), request.getRole(), request.getGrade());

        User saved = userRepo.save(user);

        return new UserUpdateResponse(saved.getId(), saved.getEmail(), saved.getName(),
                 saved.getRole(), saved.getGrade(), saved.getUpdatedAt());
    }


    @Transactional
    public void logout(String email, HttpServletRequest request, HttpServletResponse response) {
        // Access Token 헤더에서 토큰 추출
        String accessToken = resolveToken(request);
        if (accessToken != null) {
            // Access Token 블랙리스트 등록
            tokenBlacklistService.blacklistToken(accessToken);
        }

        // Refresh Token 쿠키에서 토큰 추출
        String refreshToken = cookieUtil.getRefreshTokenFromCookie(request);
        if (refreshToken != null) {
            // Refresh Token 삭제
            refreshTokenService.deleteRefreshToken(refreshToken);
        }

        // Refresh Token 쿠키 삭제
        response.addCookie(cookieUtil.deleteRefreshTokenCookie());

        // Security Context 클리어
        SecurityContextHolder.clearContext();

        log.info("로그아웃 성공: {}", email);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip.split(",")[0].trim();
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private Cookie createRefreshTokenCookie(String token) {
        return cookieUtil.createRefreshTokenCookie(
                token,
                jwtTokenProvider.getRefreshTokenValidityInMilliseconds() / 1000
        );
    }
}
