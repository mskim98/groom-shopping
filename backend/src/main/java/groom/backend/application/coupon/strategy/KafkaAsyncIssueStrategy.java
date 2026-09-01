package groom.backend.application.coupon.strategy;

import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.domain.auth.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * S2 - 판정만 응답 경로에 두고 저장을 큐 뒤로 보낸다
 *
 * <p>S1 과의 차이는 잠금의 유무가 아니라 <b>저장의 위치</b>다. 저장이 응답 경로 밖으로 나가면
 * 임계 구역을 지킬 이유가 사라지고, 그래서 잠금은 없앤 것이 아니라 <b>필요 없어진다</b>
 *
 * <p>이 전략이 만지는 자원
 * <ul>
 *   <li>거절되는 요청 - Redis 왕복 1 회. 데이터베이스에 도달하지 않는다</li>
 *   <li>통과하는 요청 - Redis 왕복 2 회(판정 · 상태 기록) + 발행 1 회. 역시 도달하지 않는다</li>
 *   <li>데이터베이스를 만지는 것은 컨슈머뿐이고, 동시에 쓰는 커넥션은 컨슈머 수만큼이다</li>
 * </ul>
 *
 * <p>대가는 방어선이 한 겹 줄어드는 것이다. S1 까지는 {@code WHERE quantity > 0} 이 뒤에서
 * 초과 발급을 흡수하고 있었고, S2 는 그 그물을 걷어내 Lua 가 유일한 재고 경계가 된다
 * 1 인 1 매는 {@code uq_coupon_issue_user} 가 그대로 지킨다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaAsyncIssueStrategy implements CouponIssueStrategy {

    private final CouponAsyncIssueService couponAsyncIssueService;

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public CouponIssueResult issue(Long couponId, User user) {
        // 접수만 한다. 확정 결과는 상태 조회로 확인한다 -
        // 여기서 확정을 기다리면 비동기로 옮긴 이유가 그대로 사라진다
        return couponAsyncIssueService.enqueue(couponId, user.getId());
    }
}
