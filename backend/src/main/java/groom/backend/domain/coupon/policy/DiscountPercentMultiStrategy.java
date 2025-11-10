package groom.backend.domain.coupon.policy;

import groom.backend.domain.coupon.model.vo.DiscountContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

// 비율 할인 정책
@Component
public class DiscountPercentMultiStrategy implements DiscountMultiStrategy {
  @Override
  public int calculateDiscount(DiscountContext discountContext) {
    double rate = discountContext.getAmount() / 100.0;
    return (int) Math.floor((discountContext.getCost() * rate) / 100) * 100; // 백원 단위 절삭
  }

  @Override
  public int calculateMultiDiscount(List<DiscountContext> discountContexts) {
    // 할인율을 동일하게 하기 위해 정렬
    List<DiscountContext> sortedContext = discountContexts.stream()
            .sorted(Comparator.comparingInt(DiscountContext::getAmount))
            .toList();
    int cost = discountContexts.get(0).getCost();

    for (DiscountContext discountContext : sortedContext) {
      double rate = discountContext.getAmount() / 100.0;
      cost = (int) Math.floor((cost * rate) / 100) * 100; // 백원 단위 절삭
    }

    return cost;
  }
}
