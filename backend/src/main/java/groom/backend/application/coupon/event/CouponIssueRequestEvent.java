package groom.backend.application.coupon.event;

// 쿠폰 비동기 발급 요청 이벤트. coupon-issue-requests 토픽으로 발행된다.
// key 를 couponId 로 두면 같은 쿠폰 요청이 같은 파티션 → 단일 컨슈머가 직렬 처리 → 분산 락 불필요.
public record CouponIssueRequestEvent(
        String requestId,
        Long couponId,
        Long userId
) {}
