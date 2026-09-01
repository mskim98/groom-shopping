package groom.backend.infrastructure.security;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 부하 측정 전용 인증 우회 - 헤더의 사용자 ID 를 그대로 믿는다
 *
 * <p>왜 필요한가: 부하 스크립트가 요청마다 로그인하면 <b>요청 1건당 사용자 조회가 2회</b>
 * (로그인 1회 + 인증 필터 1회) 붙는다. 실측에서 10만 요청에 20만 회가 찍혔다.
 * 그러면 커넥션 풀 포화가 발급 경로 때문인지 인증 때문인지 가를 수 없다.
 *
 * <p>실제 이벤트에서는 사용자가 이미 로그인한 상태로 몰려오므로, 로그인을 부하에서 빼는 편이
 * 현실에도 더 가깝다.
 *
 * <p>⚠ dev 프로파일 + 설정값을 함께 켜야만 빈이 만들어진다. 둘 중 하나라도 없으면
 * 이 클래스는 존재하지 않는다 - 헤더 하나로 남의 계정이 되는 필터이므로 조건을 두 겹으로 둔다.
 */
@Slf4j
@Component
@Profile("dev")
@ConditionalOnProperty(name = "loadtest.auth-bypass.enabled", havingValue = "true")
public class LoadTestAuthBypassFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-Lt-User-Id";

    public LoadTestAuthBypassFilter() {
        log.warn("[LOADTEST_AUTH_BYPASS_ENABLED] {} 헤더의 사용자 ID 를 검증 없이 신뢰한다. 측정 전용 설정이다",
                USER_ID_HEADER);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String raw = request.getHeader(USER_ID_HEADER);
        if (raw == null || raw.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            // DB 를 조회하지 않는다. 발급 경로가 쓰는 것은 user.getId() 뿐이라
            // 나머지 필드는 채우지 않아도 동작하고, 채우려고 조회하면 빼려던 부하가 되돌아온다
            User user = new User(Long.parseLong(raw.trim()), "lt_" + raw.trim() + "@test.com",
                    null, "lt_user", Role.ROLE_USER, Grade.BRONZE, null, null);
            CustomUserDetails principal = new CustomUserDetails(user);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority(Role.ROLE_USER.name()))));
        } catch (NumberFormatException e) {
            log.warn("[LOADTEST_AUTH_BYPASS_BAD_HEADER] value={}", raw);
        }
        chain.doFilter(request, response);
    }
}
