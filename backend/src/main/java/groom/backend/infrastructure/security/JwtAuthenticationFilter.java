package groom.backend.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import groom.backend.application.auth.service.TokenBlacklistService;
import groom.backend.common.exception.dto.ErrorResponse;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtRsaTokenProvider jwtTokenProvider;
    private final TokenBlacklistService blacklistService;

    private static final ObjectMapper LOCAL_OBJECT_MAPPER = createLocalObjectMapper();

    private static ObjectMapper createLocalObjectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return om;
    }

    private static final List<String> EXCLUDE_PATTERNS = List.of(
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/swagger-ui/**",
            "/api/v3/api-docs/**",
            "/api/swagger-ui.html",
            "/api/actuator/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();

        if ("GET".equalsIgnoreCase(request.getMethod()) &&
                (pathMatcher.match("/api/v1/product/**", path)
                        || pathMatcher.match("/api/v1/raffles/**", path))) {
            return true;
        }

        for (String pattern : EXCLUDE_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }

        return false;
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = resolveToken(request);

            if (StringUtils.hasText(token)) {

                // 1. 블랙리스트 확인
                if (blacklistService.isBlacklisted(token)) {
                    log.warn("Blacklisted token used");
                    setErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN", "인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
                    return;
                }

                if (jwtTokenProvider.validateToken(token)) {
                    if (jwtTokenProvider.isAccessToken(token)) {
                        String email = jwtTokenProvider.getEmail(token);
                        String role = jwtTokenProvider.getRole(token).name();
                        log.info("Authenticated user: {}, Role: {}", email, role);

                        Authentication authentication = jwtTokenProvider.getAuthentication(token);

                        ((AbstractAuthenticationToken) authentication)
                                .setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);

                        log.debug("Set authentication for user: {}", email);
                    } else {
                        log.warn("Refresh token used as access token");
                    }
                }
            } else {
                // 토큰이 없거나 유효하지 않은 경우
                setErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN",
                        "인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
                return;
            }

        } catch (ExpiredJwtException e) {
            // 만료된 토큰
            setErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "EXPIRED_TOKEN",
                    "세션이 만료되었습니다. 다시 로그인해 주세요.");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            // JWT 관련 예외는 이 필터에서 직접 401 응답으로 처리함
            setErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN", e.getMessage());
            return;
        } catch (Exception e) {
            // 기타 예외는 이 필터에서 직접 401 응답을 반환하여 처리합니다.
            setErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "인증 처리 중 오류가 발생했습니다.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void setErrorResponse(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        // 이전에 쓰여진 내용이 있을 수 있으니 초기화
        response.resetBuffer();
        response.setStatus(status);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");

        ErrorResponse errorResponse = ErrorResponse.of(status, code, message);
        byte[] bytes = LOCAL_OBJECT_MAPPER.writeValueAsBytes(errorResponse);

        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
        response.flushBuffer();
    }
}
