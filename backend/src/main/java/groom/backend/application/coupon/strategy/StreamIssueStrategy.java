package groom.backend.application.coupon.strategy;

import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.application.coupon.CouponIssueWaitService;
import groom.backend.domain.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 기본 발급 방식 - 판정(선점)까지만 순서대로 하고 저장은 Redis Stream 뒤에서 병렬 컨슈머가 하며, 응답은 확정까지 기다려 동기로 돌려준다
 *
 * <p>잠금이 없어진 것이 아니라 <b>저장의 위치</b>가 바뀌어 응답 경로에 필요 없어졌다.
 * 이 전략이 만지는 자원
 * <ul>
 *   <li>거절되는 요청 - Redis 왕복 1 회. 데이터베이스에 도달하지 않는다</li>
 *   <li>통과하는 요청 - Redis 왕복 1 회(판정 · 예약 · 스트림 적재 · 상태 기록이 Lua 한 번)</li>
 *   <li>데이터베이스를 만지는 것은 스트림 컨슈머뿐이고, 한 쿠폰의 한 배치를 한 트랜잭션으로 넣는다</li>
 *   <li>접수 뒤 요청은 자기 완료 신호를 기다려 SUCCESS · FAILED 로 답한다. 기다림이 실패하면 요청 스레드가 자기 행을 직접 넣는다
 *       ({@link CouponIssueWaitService})</li>
 *   <li>Redis 가 끊기면 접수 진입점이 {@link DbLockIssueStrategy} 경로로 대신 발급한다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StreamIssueStrategy implements CouponIssueStrategy {

    private final CouponAsyncIssueService couponAsyncIssueService;
    private final CouponIssueWaitService waitService;

    @Override
    public String name() {
        return "stream";
    }

    @Override
    public CouponIssueResult issue(Long couponId, User user) {
        CouponIssueResult accepted = couponAsyncIssueService.enqueue(couponId, user.getId());
        if (accepted.status() != CouponIssueResult.Status.ACCEPTED) {
            // 접수 게이트의 거절(품절 · 중복 · 미초기화)이나 Redis 장애의 DB 경로 결과는 이미 최종이다
            return accepted;
        }
        // 선점은 끝났다. 저장은 컨슈머가 하고, 이 요청은 자기 확정을 기다려 최종 결과를 돌려준다
        return waitService.awaitFinal(couponId, user.getId(), accepted.requestId());
    }
}
