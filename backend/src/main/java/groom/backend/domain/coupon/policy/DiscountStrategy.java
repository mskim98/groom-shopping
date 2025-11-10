package groom.backend.domain.coupon.policy;

// 전략 패턴 인터페이스
public interface DiscountStrategy {
  int calculateDiscount(int cost, int amount);
}

