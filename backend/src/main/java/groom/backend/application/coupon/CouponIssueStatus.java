package groom.backend.application.coupon;

/**
 * 클라이언트가 폴링하는 발급 상태 문자열 - 접수 Lua · 스트림 컨슈머 · 상태 조회가 같은 값을 쓴다
 *
 * <p>접수 스크립트는 {@link #WAITING} 을 인자로 받아 쓰고, 컨슈머는 확정 뒤 {@link #SUCCESS} 또는
 * {@link #FAILED_PREFIX} + 사유를 쓴다. 상태 키가 없으면 {@link #UNKNOWN} 이다
 */
public final class CouponIssueStatus {

    public static final String WAITING = "WAITING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED_PREFIX = "FAILED:";
    public static final String UNKNOWN = "UNKNOWN";

    private CouponIssueStatus() {
    }
}
