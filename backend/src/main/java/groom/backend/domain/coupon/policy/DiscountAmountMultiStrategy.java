package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.vo.DiscountContext;
import org.springframework.stereotype.Component;

import java.util.List;

// 정액 할인 정책
@Component
public class DiscountAmountMultiStrategy implements DiscountMultiStrategy {
  @Override
  public int calculateDiscount(DiscountContext discountContext) {
    return discountContext.getAmount(); // amount 만큼 차감
  }

  @Override
  public int calculateMultiDiscount(List<DiscountContext> discountContexts) {
    return discountContexts.stream()
            .mapToInt(DiscountContext::getAmount)
            .sum();
  }
}

