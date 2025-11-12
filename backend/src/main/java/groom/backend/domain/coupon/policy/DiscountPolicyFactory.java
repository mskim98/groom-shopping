package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.enums.CouponType;

public class DiscountPolicyFactory {
  public static DiscountPolicy getDiscountStrategy(CouponType couponType) {
    return switch (couponType) {
      case DISCOUNT -> new DiscountAmountMultiPolicy();
      case PERCENT -> new DiscountPercentMultiPolicy();
      case MIN_COST_AMOUNT -> new DiscountAmountMultiPolicy();
      case MAX_DISCOUNT_PERCENT -> new DiscountAmountMultiPolicy();
    };
  }

  public static DiscountMultiPolicy getDiscountMultiStrategy(CouponType couponType) {
    return switch (couponType) {
      case DISCOUNT -> new DiscountAmountMultiPolicy();
      case PERCENT -> new DiscountPercentMultiPolicy();
      default -> null;
    };
  }
}
