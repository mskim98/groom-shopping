package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.vo.DiscountContext;
import org.springframework.stereotype.Component;

// 정액 할인 정책
@Component
public class DiscountAmountMultiStrategy implements DiscountMultiStrategy {
  @Override
  public int calculateDiscount(DiscountContext discountContext) {
    return discountContext.getAmount(); // amount 만큼 차감
  }
}

