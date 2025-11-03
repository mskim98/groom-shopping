package groom.backend.application.coupon;

import groom.backend.domain.auth.entity.User;
import groom.backend.domain.coupon.entity.Coupon;
import groom.backend.domain.coupon.entity.CouponIssue;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponIssueService {
  private final CouponRepository couponRepository;
  private final CouponIssueRepository  couponIssueRepository;

  @Transactional
  public CouponIssueResponse issueCoupon(Long couponId, User user) {
    // 쿠폰 조회
    Coupon coupon = couponRepository.findById(couponId).orElse(null);

    if (coupon == null) {
      return null;
    }

    // 활성화 여부 확인
    // TODO : 비활성화 상태일 시 404 Not Found Exception 발생

    // 수량 확인
    // TODO : 수량이 0보다 낮거나 같은 경우 409 Conflict 발생 및 재고 소진 메시지 반환

    // 쿠폰 확보
    coupon.decreaseQuantity();

    // 쿠폰 등록 및 DB 적용
    // TODO : 쿠폰 만료일은 정책 관련 사항
    // coupon 만료일은 00:00을 기준으로 함. LocalDate는 시간이 존재하지 않으므로 생성 시 시간을 추가하여 생성
    CouponIssue couponIssue = couponIssueRepository.save(CouponIssue.builder()
            .couponId(couponId)
            .userId(user.getId())
            .createdAt(LocalDateTime.now())
            .deletedAt(LocalDateTime.of(coupon.getExpireDate(), LocalTime.MIN))
            .build());
    couponRepository.save(coupon);


    // 확보된 쿠폰 반환
    return CouponIssueResponse.from(couponIssue);
  }

  public List<CouponIssueResponse> searchMyCoupon(Long userId) {
    // 유저 id로 쿠폰 조회
    // 현재 사용 가능한 (만료 시간, 활성화 여부) 쿠폰 조회
    return couponIssueRepository.findByUserIdAndIsActiveTrueAndDeletedAtAfter(userId, LocalDateTime.now()).stream().map(CouponIssueResponse::from).collect(Collectors.toList());
  }
}
