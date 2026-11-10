package groom.backend.application.coupon.strategy;

import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.domain.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 기본 발급 방식 - 판정만 응답 경로에 두고 저장을 Redis Stream 뒤로 보낸다
 *
 * <p>잠금이 없어진 것이 아니라 <b>저장의 위치</b>가 바뀌어 응답 경로에 필요 없어졌다.
 * 이 전략이 만지는 자원
 * <ul>
 *   <li>거절되는 요청 - Redis 왕복 1 회. 데이터베이스에 도달하지 않는다</li>
 *   <li>통과하는 요청 - Redis 왕복 1 회(판정 · 예약 · 스트림 적재 · 상태 기록이 Lua 한 번)</li>
 *   <li>데이터베이스를 만지는 것은 스트림 컨슈머뿐이고, 한 쿠폰의 한 배치를 한 트랜잭션으로 넣는다</li>
 *   <li>Redis 가 끊기면 접수 진입점이 {@link DbLockIssueStrategy} 경로로 대신 발급한다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StreamIssueStrategy implements CouponIssueStrategy {

    private final CouponAsyncIssueService couponAsyncIssueService;

    @Override
    public String name() {
        return "stream";
    }

    @Override
    public CouponIssueResult issue(Long couponId, User user) {
        // 접수만 한다. 확정 결과는 상태 조회로 확인한다 -
        // 여기서 확정을 기다리면 비동기로 옮긴 이유가 그대로 사라진다
        return couponAsyncIssueService.enqueue(couponId, user.getId());
    }
}
