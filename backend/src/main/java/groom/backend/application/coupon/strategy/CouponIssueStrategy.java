package groom.backend.application.coupon.strategy;

import groom.backend.domain.auth.entity.User;

/**
 * 쿠폰 발급 방식 하나를 나타낸다
 *
 * <p>구현체마다 임계 구역의 위치가 다르다 - 그 차이가 곧 측정 대상이다
 * 라운드를 바꿔 가며 재려면 호출부가 같아야 하므로 시그니처를 하나로 고정한다
 */
public interface CouponIssueStrategy {

    /**
     * application.yml 의 {@code coupon.issue.strategy} 값과 대응한다
     */
    String name();

    CouponIssueResult issue(Long couponId, User user);
}
