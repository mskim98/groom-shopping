package groom.backend.application.coupon.event;

// 쿠폰 비동기 발급 결과 이벤트. coupon-issue-results 토픽으로 발행된다.
// status: SUCCESS / FAILED. reason: 실패 사유(에러 코드명) 또는 null.
public record CouponIssueResultEvent(
        String requestId,
        Long couponId,
        Long userId,
        String status,
        String reason
) {}
