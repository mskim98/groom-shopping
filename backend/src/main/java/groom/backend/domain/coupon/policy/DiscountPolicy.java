package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.vo.DiscountContext;

// 전략 패턴 인터페이스
public interface DiscountPolicy {
  /**
   * 할인 금액을 계산합니다.
   * @param discountContext 할인 금액 정보를 담는 vo
   * @return
   */
  int calculateDiscount(DiscountContext discountContext);
}

