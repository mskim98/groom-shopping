package groom.backend.application.auth.service;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.auth.repository.RefreshTokenRepository;
import groom.backend.domain.auth.repository.UserRepository;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.infrastructure.security.JwtTokenProvider;
import groom.backend.interfaces.auth.dto.request.LoginRequest;
import groom.backend.interfaces.auth.dto.request.SignUpRequest;
import groom.backend.interfaces.auth.dto.request.TokenRefreshRequest;
import groom.backend.interfaces.auth.dto.request.UserUpdateRequest;
import groom.backend.interfaces.auth.dto.response.LoginResponse;
import groom.backend.interfaces.auth.dto.response.SignUpResponse;
import groom.backend.interfaces.auth.dto.response.TokenRefreshResponse;
import groom.backend.interfaces.auth.dto.response.UserUpdateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthApplicationService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public SignUpResponse register(SignUpRequest req) {
        String email = req.getEmail().toLowerCase().trim();

        if (userRepo.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered");
        }

        // TODO 객체 생성 팩토리 메서드로 변경 고려
        User user = new User(
                null,
                email,
                passwordEncoder.encode(req.getPassword()),
                req.getName(),
                Role.ROLE_USER,
                Grade.BRONZE,
                null,
                null
        );

        User saved = userRepo.save(user);

        return new SignUpResponse(saved.getId(), saved.getEmail(), saved.getName(),
                 saved.getGrade(), saved.getCreatedAt());

    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        Object principal = authentication.getPrincipal();
        User user = ((CustomUserDetails) principal).getUser();

        // Access Token 생성
        String accessToken = jwtTokenProvider.createAccessToken(user.getEmail(), user.getRole());

        // Refresh Token 생성 및 Redis 저장
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getEmail(), user.getRole());

        refreshTokenRepository.save(user.getEmail(), refreshToken, jwtTokenProvider.getRefreshTokenValidityInMilliseconds());

        // TODO :: refreshToken cookie 처리
        // reponse로 refreshToken같이보내면 보안상 이슈
        // 따라서 refreshToken은 HttpOnly Cookie에 저장
        //cookieUtil.addRefreshTokenCookie(response, refreshToken);

        log.info("로그인 성공: {}", user.getEmail());

        return new LoginResponse(accessToken, refreshToken, user.getName(), user.getRole());
    }

    @Transactional
    public TokenRefreshResponse refreshToken(TokenRefreshRequest request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("유효하지 않은 리프레시 토큰입니다.");
        }

        String email = jwtTokenProvider.getEmail(refreshToken);
        String storedRefreshToken = refreshTokenRepository.findByEmail(email)
                                            .orElseThrow(() -> new IllegalArgumentException("저장된 리프레시 토큰을 찾을 수 없습니다."));

        if (!storedRefreshToken.equals(refreshToken)) {
            throw new IllegalArgumentException("리프레시 토큰이 일치하지 않습니다.");
        }

        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        String newAccessToken = jwtTokenProvider.createAccessToken(user.getEmail(), user.getRole());

        log.info("액세스 토큰 재발급: {}", email);

        return new TokenRefreshResponse(newAccessToken);
    }

    @Transactional
    public UserUpdateResponse updateUser(Long id, UserUpdateRequest request) {
        User user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        user.updateUser(request.getName(), request.getRole(), request.getGrade());

        User saved = userRepo.save(user);

        return new UserUpdateResponse(saved.getId(), saved.getEmail(), saved.getName(),
                 saved.getRole(), saved.getGrade(), saved.getUpdatedAt());
    }


    @Transactional
    public void logout(String email) {
        refreshTokenRepository.deleteByEmail(email);
        // TODO :: refreshToken cookie 처리
        // cookieUtil.deleteRefreshTokenCookie(response);
        log.info("로그아웃 성공: {}", email);
    }
}
