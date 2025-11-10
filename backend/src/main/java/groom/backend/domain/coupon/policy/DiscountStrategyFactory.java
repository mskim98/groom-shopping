package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.enums.CouponType;

public class DiscountStrategyFactory {
  public static DiscountStrategy getDiscountStrategy(CouponType couponType) {
    return switch (couponType) {
      case DISCOUNT -> new DiscountAmountMultiStrategy();
      case PERCENT -> new DiscountPercentMultiStrategy();
    };
  }
}
