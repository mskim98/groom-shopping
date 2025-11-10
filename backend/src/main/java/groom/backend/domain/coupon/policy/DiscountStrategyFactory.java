package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.enums.CouponType;

public class DiscountStrategyFactory {
  public static DiscountStrategy getDiscountStrategy(CouponType couponType) {
    return switch (couponType) {
      case DISCOUNT -> new DiscountAmountMultiStrategy();
      case PERCENT -> new DiscountPercentMultiStrategy();
      case MIN_COST_AMOUNT -> new DiscountAmountMultiStrategy();
      case MAX_DISCOUNT_PERCENT -> new DiscountAmountMultiStrategy();
    };
  }

  public static DiscountMultiStrategy getDiscountMultiStrategy(CouponType couponType) {
    return switch (couponType) {
      case DISCOUNT -> new DiscountAmountMultiStrategy();
      case PERCENT -> new DiscountPercentMultiStrategy();
      default -> null;
    };
  }
}
