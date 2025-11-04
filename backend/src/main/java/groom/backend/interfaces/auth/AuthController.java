package groom.backend.interfaces.auth;

import groom.backend.application.auth.service.AuthApplicationService;
import groom.backend.infrastructure.security.CustomUserDetails;
import groom.backend.interfaces.auth.dto.request.LoginRequest;
import groom.backend.interfaces.auth.dto.request.SignUpRequest;
import groom.backend.interfaces.auth.dto.request.TokenRefreshRequest;
import groom.backend.interfaces.auth.dto.request.UserUpdateRequest;
import groom.backend.interfaces.auth.dto.response.LoginResponse;
import groom.backend.interfaces.auth.dto.response.SignUpResponse;
import groom.backend.interfaces.auth.dto.response.TokenRefreshResponse;
import groom.backend.interfaces.auth.dto.response.UserUpdateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthApplicationService authService;

    @PostMapping("/signup")
    public ResponseEntity<SignUpResponse> signUp(@RequestBody @Valid SignUpRequest request) {
        SignUpResponse response = authService.register(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse loginResponse = authService.login(request);
        return ResponseEntity.ok(loginResponse);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenRefreshResponse> refresh(@RequestBody @Valid TokenRefreshRequest request) {
        TokenRefreshResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CustomUserDetails user) {
        if (user == null || user.getUser() == null || user.getUser().getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        authService.logout(user.getUser().getEmail());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/users")
    public ResponseEntity<UserUpdateResponse> update(@AuthenticationPrincipal CustomUserDetails user,
                                                     @RequestBody UserUpdateRequest userUpdateRequest) {
        if (user == null || user.getUser() == null || user.getUser().getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UserUpdateResponse response = authService.updateUser(user.getUser().getId(), userUpdateRequest);
        return ResponseEntity.ok(response);
    }
}
