package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.vo.DiscountContext;

public class DiscountPercentMaximumDiscountSingleStrategy implements DiscountSingleStrategy{

  @Override
  public int calculateDiscount(DiscountContext discountContext) {
    double rate = discountContext.getAmount() / 100.0;
    int discount = (int) Math.floor((discountContext.getCost() * rate) / 100) * 100; // 백원 단위 절삭
    return Math.min(discount, discountContext.getMaximumDiscount());
  }
}
