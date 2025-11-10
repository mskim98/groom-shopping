package groom.backend.domain.coupon.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class DiscountContext {
  private int cost;
  private int amount;

  private Integer maximumDiscount; // 최대 할인액
  private Integer minimumCost; // 최소 구매금액
  // TODO : 정책 미구현
//  private String targetProduct; // 할인 대상 상품
}
