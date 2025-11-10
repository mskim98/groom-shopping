package groom.backend.domain.coupon.policy;

import org.springframework.stereotype.Component;

// 정액 할인 정책
@Component
public class DiscountAmountStrategy implements DiscountMultiStrategy {
  @Override
  public int calculateDiscount(int cost, int amount) {
    return amount; // amount 만큼 차감
  }
}

