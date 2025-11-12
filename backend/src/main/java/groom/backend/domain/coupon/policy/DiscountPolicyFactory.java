package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.enums.CouponType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiscountPolicyFactory {
  private final DiscountAmountMultiPolicy discountAmountMultiPolicy;
  private final DiscountPercentMultiPolicy discountPercentMultiPolicy;
  private final DiscountAmountMinCostSinglePolicy discountAmountMinCostSinglePolicy;
  private final DiscountPercentMaximumDiscountSinglePolicy discountPercentMaximumDiscountSinglePolicy;


  public DiscountPolicy getDiscountStrategy(CouponType couponType) {
    return switch (couponType) {
      case DISCOUNT -> discountAmountMultiPolicy;
      case PERCENT -> discountPercentMultiPolicy;
      case MIN_COST_AMOUNT -> discountAmountMinCostSinglePolicy;
      case MAX_DISCOUNT_PERCENT -> discountPercentMaximumDiscountSinglePolicy;
    };
  }

  public DiscountMultiPolicy getDiscountMultiStrategy(CouponType couponType) {
    return switch (couponType) {
      case DISCOUNT ->discountAmountMultiPolicy;
      case PERCENT -> discountPercentMultiPolicy;
      default -> null;
    };
  }
}
