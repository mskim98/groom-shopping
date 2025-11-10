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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


@Tag(name = "Auth", description = "회원가입, 로그인, 토큰 갱신, 로그아웃, 사용자 정보 수정 관련 API")
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthApplicationService authService;


    @Operation(
            summary = "회원가입",
            description = "사용자 정보를 입력받아 새로운 계정을 생성합니다.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "회원가입 성공",
                            content = @Content(schema = @Schema(implementation = SignUpResponse.class)))
            }
    )
    @PostMapping("/signup")
    public ResponseEntity<SignUpResponse> signUp(@RequestBody @Valid SignUpRequest request) {
        SignUpResponse response = authService.register(request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "로그인",
            description = "이메일과 비밀번호로 로그인하고 액세스 토큰 및 리프레시 토큰을 발급받습니다.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "로그인 성공",
                            content = @Content(schema = @Schema(implementation = LoginResponse.class)))
            }
    )
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse loginResponse = authService.login(request);
        return ResponseEntity.ok(loginResponse);
    }

    @Operation(
            summary = "토큰 갱신",
            description = "리프레시 토큰을 이용해 새로운 액세스 토큰을 발급받습니다.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "토큰 갱신 성공",
                            content = @Content(schema = @Schema(implementation = TokenRefreshResponse.class)))
            }
    )
    @PostMapping("/refresh")
    public ResponseEntity<TokenRefreshResponse> refresh(@RequestBody @Valid TokenRefreshRequest request) {
        TokenRefreshResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "로그아웃",
            description = "현재 로그인한 사용자의 세션 또는 토큰을 무효화합니다.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "로그아웃 성공"),
                    @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
            }
    )
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Parameter(hidden = true)
            @AuthenticationPrincipal CustomUserDetails user
    ) {
        if (user == null || user.getUser() == null || user.getUser().getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        authService.logout(user.getUser().getEmail());
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "사용자 정보 수정",
            description = "회원 권한과 등급을 수정합니다.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "정보 수정 성공",
                            content = @Content(schema = @Schema(implementation = UserUpdateResponse.class))),
                    @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
            }
    )
    @PutMapping("/users")
    public ResponseEntity<UserUpdateResponse> update(
            @Parameter(hidden = true)
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestBody UserUpdateRequest userUpdateRequest
    ) {
        if (user == null || user.getUser() == null || user.getUser().getEmail() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UserUpdateResponse response = authService.updateUser(user.getUser().getId(), userUpdateRequest);
        return ResponseEntity.ok(response);
    }
}
