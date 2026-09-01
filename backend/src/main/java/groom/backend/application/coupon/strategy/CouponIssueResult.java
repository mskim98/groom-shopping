package groom.backend.application.coupon.strategy;

/**
 * 발급 전략의 반환값
 *
 * <p>CONTENTION 을 SOLD_OUT 과 나누는 것이 이 타입의 핵심이다
 * 현행 코드는 락 실패 · 재고 소진 · 인터럽트 · DB 수량 부족을 모두 같은 오류로 돌려주는데,
 * 그러면 라운드를 옮길 때 늘어난 실패가 경합인지 품절인지 구별할 수단이 없어 비교 자체가 성립하지 않는다
 */
public record CouponIssueResult(Status status, Long couponIssueId, String requestId) {

    public enum Status {
        SUCCESS,
        // 접수만 끝났고 확정은 아직이다. 비동기 확정 경로에서만 쓴다
        ACCEPTED,
        SOLD_OUT,
        ALREADY_ISSUED,
        // 재고는 남아 있는데 잠금을 제한 시간 안에 얻지 못해 처리하지 못한 경우
        // 사용자에게는 품절과 같은 409 로 보이지만 원인이 다르므로 따로 센다
        CONTENTION,
        // 재고 키가 없어 판정 자체가 불가능한 상태. 거절이 아니라 보류다
        // 접수 경로가 여기서 기다리면 그 대기가 스레드 점유로 바뀌므로 즉시 503 으로 돌려보낸다
        UNAVAILABLE
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

    public static CouponIssueResult unavailable() {
        return new CouponIssueResult(Status.UNAVAILABLE, null, null);
    }
}
