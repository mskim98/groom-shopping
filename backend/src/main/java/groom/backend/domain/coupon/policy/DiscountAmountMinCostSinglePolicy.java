package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.vo.DiscountContext;
import org.springframework.stereotype.Component;

@Component
public class DiscountAmountMinCostSinglePolicy implements DiscountSinglePolicy {

  @Override
  public int calculateDiscount(DiscountContext discountContext) {
    return discountContext.getCost() > discountContext.getMinimumCost() ?
            discountContext.getAmount() : 0;
  }
}
