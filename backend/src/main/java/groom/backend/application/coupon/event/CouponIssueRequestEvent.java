package groom.backend.application.coupon.event;

// 쿠폰 비동기 발급 요청 이벤트. coupon-issue-requests 토픽으로 발행된다.
// key 를 couponId 로 두면 같은 쿠폰 요청이 같은 파티션 → 단일 컨슈머가 직렬 처리 → 분산 락 불필요.
//
// gateReserved : 접수 단계의 Lua 게이트가 이미 재고를 깎았는가
// true 면 컨슈머는 게이트를 다시 돌리지 않고 DB 확정만 한다 (두 번 깎으면 재고가 실제보다 빨리 마감된다)
// 배포 전에 실린 구 메시지는 이 필드가 없어 false 로 역직렬화되고, 컨슈머가 DB 폴백으로 안전하게 처리한다
public record CouponIssueRequestEvent(
        String requestId,
        Long couponId,
        Long userId,
        boolean gateReserved
) {}
