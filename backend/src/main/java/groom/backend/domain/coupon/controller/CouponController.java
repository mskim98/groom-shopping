package groom.backend.domain.coupon.controller;

import groom.backend.domain.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/coupon")
public class CouponController {
  public final CouponService couponService;

  @PostMapping("/issue/{coupon_id}")
  public String issueCoupon(@PathVariable("coupon_id") Long couponId) {
    // 헤더의 날짜 및 credential 추출

    // credential 유효성 검사

    // 날짜 검증

    // 쿠폰 발급
    couponService.issueCoupon(couponId);
    return "coupon issue";
  }

  @GetMapping("/me")
  public String myCoupon() {
    // credential 추출
    Long userId = 1L;
    // credential 유효성 검사

    // 내 미사용 쿠폰 조회
    couponService.searchMyCoupon(userId);

    return "my coupon";
  }

}
