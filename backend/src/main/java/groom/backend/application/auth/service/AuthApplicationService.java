package groom.backend.application.auth.service;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.auth.repository.UserRepository;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.infrastructure.security.JwtTokenProvider;
import groom.backend.interfaces.auth.dto.request.LoginRequest;
import groom.backend.interfaces.auth.dto.request.SignUpRequest;
import groom.backend.interfaces.auth.dto.response.LoginResponse;
import groom.backend.interfaces.auth.dto.response.SignUpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
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
        User user;

        if (principal instanceof CustomUserDetails) {
            user = ((CustomUserDetails) principal).getUser();
        } else if (principal instanceof User) {
            user = (User) principal;
        } else if (principal instanceof UserDetails) {
            String email = ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
            user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new IllegalStateException("User not found for email: " + email));
        } else {
            throw new IllegalStateException("Unsupported principal type: " + (principal == null ? "null" : principal.getClass()));
        }

        // Access Token 생성
        String accessToken = jwtTokenProvider.createAccessToken(user.getEmail(), user.getRole());

        // Refresh Token 생성 및 Redis 저장
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getEmail(), user.getRole());

        //refreshTokenRepository.save(user.getEmail(), refreshToken, jwtTokenProvider.getRefreshTokenValidityInMilliseconds());

        // TODO :: refreshToken cookie 처리
        // reponse로 refreshToken같이보내면 보안상 이슈
        // 따라서 refreshToken은 HttpOnly Cookie에 저장
        //cookieUtil.addRefreshTokenCookie(response, refreshToken);

        log.info("로그인 성공: {}", user.getEmail());

        return new LoginResponse(accessToken, refreshToken, user.getName(), user.getRole());
    }
}
