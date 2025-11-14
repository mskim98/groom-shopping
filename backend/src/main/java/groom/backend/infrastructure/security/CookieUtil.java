package groom.backend.infrastructure.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class CookieUtil {

    public static final String REFRESH_TOKEN_COOKIE_NAME = "refreshToken";

    /**
     * Refresh Token을 저장하는 HttpOnly 쿠키 생성
     */
    public Cookie createRefreshTokenCookie(String refreshToken, long maxAgeInSeconds) {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE_NAME, refreshToken);

        cookie.setHttpOnly(true);
        cookie.setPath("/"); // 모든 경로에서 접근 가능
        cookie.setMaxAge((int) maxAgeInSeconds);
        cookie.setAttribute("SameSite", "lax");
        // 필요에 따라 Secure 속성 설정 (예: HTTPS 환경에서만 전송)
        // cookie.setSecure(true);
        return cookie;
    }

    /**
     * Refresh Token 쿠키 삭제
     */
    public Cookie deleteRefreshTokenCookie() {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE_NAME, null);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0); // 즉시 만료
        cookie.setAttribute("SameSite", "Strict");
        // 필요에 따라 Secure 속성 설정 (예: HTTPS 환경에서만 전송)
        // cookie.setSecure(true);
        return cookie;
    }

    /**
     * 요청에서 Refresh Token 쿠키 추출
     */
    public String getRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }

        return Arrays.stream(request.getCookies())
                .filter(cookie -> REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }

    /**
     * Refresh Token 쿠키 존재 여부 확인
     */
    public boolean hasRefreshTokenCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return false;
        }

        return Arrays.stream(request.getCookies())
                .anyMatch(cookie -> REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName()));
    }

}
