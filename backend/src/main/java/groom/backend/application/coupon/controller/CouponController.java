package groom.backend.application.coupon.controller;

import groom.backend.application.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class CouponController {
  public final CouponService couponService;
}
