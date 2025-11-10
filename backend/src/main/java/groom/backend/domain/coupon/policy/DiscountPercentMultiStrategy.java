package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.vo.DiscountContext;
import org.springframework.stereotype.Component;

// 비율 할인 정책
@Component
public class DiscountPercentMultiStrategy implements DiscountMultiStrategy {
  @Override
  public int calculateDiscount(DiscountContext discountContext) {
    double rate = discountContext.getAmount() / 100.0;
    return (int) Math.floor((discountContext.getCost() * rate) / 100) * 100; // 백원 단위 절삭
  }
}
