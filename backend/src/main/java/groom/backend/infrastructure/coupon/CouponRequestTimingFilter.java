package groom.backend.infrastructure.coupon;

import groom.backend.application.coupon.CouponRedisLatency;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 쿠폰 접수(POST) · 상태 조회(GET .../status) 요청이 컨트롤러에 들어와 응답이 나갈 때까지의 시간을 단계 타이머에 기록한다
 * (서비스 안의 Redis 호출 시간과 나란히 보면 요청 시간 중 Redis 밖에서 쓴 몫이 나온다)
 */
@Component
public class CouponRequestTimingFilter extends OncePerRequestFilter {

    private static final String PATH = "/coupon/issue-async/";

    private final CouponRedisLatency latency;

    public CouponRequestTimingFilter(CouponRedisLatency latency) {
        this.latency = latency;
    }

    private static String stageOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.contains(PATH)) {
            return null;
        }
        if ("POST".equals(request.getMethod())) {
            return "accept_request";
        }
        return "GET".equals(request.getMethod()) && uri.endsWith("/status") ? "status_request" : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return stageOf(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            latency.record(stageOf(request), System.nanoTime() - started);
        }
    }
}
