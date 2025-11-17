package groom.backend.domain.coupon.mapper;

import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.vo.DiscountContext;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;

public class CouponContextMapper {
  public static DiscountContext from(CouponIssueResponse coupon, int cost) {
    return DiscountContext.builder()
            .cost(cost)
            .amount(coupon.getDiscountValue())
            .maximumDiscount(coupon.getMaximumDiscount())
            .minimumCost(coupon.getMinimumCost())
            .build();
  }
}
