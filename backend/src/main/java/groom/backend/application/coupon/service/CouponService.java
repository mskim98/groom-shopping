package groom.backend.application.coupon.service;

import groom.backend.application.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponService {
  private final CouponRepository couponRepository;
}
