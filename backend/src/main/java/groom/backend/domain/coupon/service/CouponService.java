package groom.backend.domain.coupon.service;

import groom.backend.domain.coupon.entity.Coupon;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponService {
  private final CouponRepository couponRepository;
  private final CouponIssueRepository couponIssueRepository;

  public void issueCoupon(Long couponId) {
    // 쿠폰 조회
    Coupon coupon = couponRepository.findById(couponId).orElse(null);

    // 조회되지 않을 시 Exception 발생

    // 활성화 여부 확인

    // 수량 매진 여부 확인

    // 쿠폰 확보

    // 쿠폰 등록

    // 확보된 쿠폰 반환
  }

  public void searchMyCoupon(Long userId) {
    // 유저 id로 쿠폰 조회
    // 현재 사용 가능한 (만료 시간, 활성화 여부) 쿠폰 조회
    couponIssueRepository.findCouponIssueByUserId(userId);

    // 결과 반환
  }
}
