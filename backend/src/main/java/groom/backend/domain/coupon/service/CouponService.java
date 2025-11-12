package groom.backend.domain.coupon.service;

import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.kafka.CouponDelayProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {
  private final CouponRepository couponRepository;

  /**
   * 쿠폰 상태 비활성화 메서드입니다.
   * 성공 시 true, 실패 시 false를 반환합니다.
   * @param couponId
   * @return
   */
  @Transactional
  public Boolean disableCoupon(Long couponId) {
    // 쿠폰 id가 unique 하므로, 수정될 시 1임이 보장됨. 0일 경우 DB에 존재하지 않거나 쿼리가 실패한 경우.
    return couponRepository.updateIsActiveFalse(couponId) > 0;
  }
}
