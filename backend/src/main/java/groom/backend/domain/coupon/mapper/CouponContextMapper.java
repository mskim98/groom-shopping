package groom.backend.domain.coupon.mapper;

import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.vo.DiscountContext;

public class CouponContextMapper {
  public static DiscountContext from(Coupon coupon, int cost) {
    return DiscountContext.builder()
            .cost(cost)
            .amount(coupon.getAmount())
            .maximumDiscount(coupon.getMaximumDiscount())
            .minimumCost(coupon.getMinimumCost())
            .build();
  }
}
