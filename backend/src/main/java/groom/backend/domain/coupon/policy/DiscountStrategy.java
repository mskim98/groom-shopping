package groom.backend.domain.coupon.policy;

// 전략 패턴 인터페이스
public interface DiscountStrategy {
  /**
   * 할인 금액을 계산합니다.
   * @param cost 총 구매 금액
   * @param amount 정책에 사용되는 할인 매개변수
   * @return
   */
  int calculateDiscount(int cost, int amount);
}

