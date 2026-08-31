package groom.backend.application.coupon.strategy;

/**
 * 발급 전략의 반환값
 *
 * <p>CONTENTION 을 SOLD_OUT 과 나누는 것이 이 타입의 핵심이다
 * 현행 코드는 락 실패 · 진짜 품절 · 인터럽트 · DB 수량 부족을 모두 같은 오류로 돌려주는데,
 * 그러면 라운드를 옮길 때 늘어난 실패가 경합인지 품절인지 구별할 수단이 없어 비교 자체가 성립하지 않는다
 */
public record CouponIssueResult(Status status, Long couponIssueId, String requestId) {

    public enum Status {
        SUCCESS,
        // 접수만 끝났고 확정은 아직이다. 비동기 확정 경로에서만 쓴다
        ACCEPTED,
        SOLD_OUT,
        ALREADY_ISSUED,
        // 재고는 남았는데 경합으로 실패한 것 = 거짓 품절
        CONTENTION
    }

    public static CouponIssueResult success(Long couponIssueId) {
        return new CouponIssueResult(Status.SUCCESS, couponIssueId, null);
    }

    public static CouponIssueResult accepted(String requestId) {
        return new CouponIssueResult(Status.ACCEPTED, null, requestId);
    }

    public static CouponIssueResult soldOut() {
        return new CouponIssueResult(Status.SOLD_OUT, null, null);
    }

    public static CouponIssueResult alreadyIssued() {
        return new CouponIssueResult(Status.ALREADY_ISSUED, null, null);
    }

    public static CouponIssueResult contention() {
        return new CouponIssueResult(Status.CONTENTION, null, null);
    }
}
