package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.vo.DiscountContext;

import java.util.List;

// 다수 쿠폰 사용 전략
public interface DiscountMultiStrategy extends DiscountStrategy {
  public int calculateMultiDiscount(List<DiscountContext> discountContexts);
}
