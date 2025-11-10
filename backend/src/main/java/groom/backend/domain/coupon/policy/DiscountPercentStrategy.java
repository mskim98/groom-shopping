package groom.backend.domain.coupon.policy;

import org.springframework.stereotype.Component;

// 비율 할인 정책
@Component
public class DiscountPercentStrategy implements DiscountMultiStrategy {
  @Override
  public int calculateDiscount(int cost, int amount) {
    double rate = amount / 100.0;
    return (int) Math.floor((cost * rate) / 100) * 100; // 백원 단위 절삭
  }
}
