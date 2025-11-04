package groom.backend.application.coupon;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.coupon.entity.Coupon;
import groom.backend.domain.coupon.entity.CouponIssue;
import groom.backend.domain.coupon.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 사용자에게 발급된 쿠폰을 관리하는 서비스
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponIssueService {
  private final CouponRepository couponRepository;
  private final CouponIssueRepository  couponIssueRepository;

  @Transactional
  public CouponIssueResponse issueCoupon(Long couponId, User user) {
    // 쿠폰 조회
    // 비관적 락을 이용해 리소스 점유, 자원 충돌 ( quantity <= 0 ) 케이스 방지
    Coupon coupon = couponRepository.findByIdForUpdate(couponId).orElseThrow(
            ()-> new BusinessException(ErrorCode.NOT_FOUND)
    );

    // 활성화 여부 확인
    if (!coupon.getIsActive()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }

    // 수량 확인
    if (coupon.getQuantity() <= 0) {
      throw new BusinessException(ErrorCode.CONFLICT, "수량이 소진되었습니다.");
    }

    // 쿠폰 확보
    // 분산 아키텍처에서는 여전히 동시성 문제 발생 가능
    coupon.decreaseQuantity();

    // 쿠폰 등록 및 DB 적용
    // TODO : 쿠폰 만료일은 정책 관련 사항
    // coupon 만료일은 00:00을 기준으로 함. LocalDate는 시간이 존재하지 않으므로 생성 시 시간을 추가하여 생성
    CouponIssue couponIssue = couponIssueRepository.save(CouponIssue.builder()
            .coupon(coupon)
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

  // 쿠폰 사용을 위한 할인 금액 조회
  // 임시 : 사용 가능 여부 반환 시 사용하지 못할 경우 -1 반환
  // TODO : 여러 쿠폰 사용 가능하도록 개선
  public Integer calculateDiscount(Long couponId, Long userId, Integer Cost) {
    // 쿠폰 조회
    CouponIssue couponIssue = couponIssueRepository.findById(couponId).orElse(null);
    if (couponIssue == null) {return -1;}

    // 쿠폰 검증
    // 사용자 확인, 활성화 여부 확인
    if (couponIssue.getUserId().equals(userId) && couponIssue.getIsActive()) {
      Integer discount = 0;

      // 할인율 계산 로직
      // TODO : 정책 리팩토링
      switch(couponIssue.getCoupon().getType()) {
        case CouponType.DISCOUNT -> discount = couponIssue.getCoupon().getAmount();
        case CouponType.PERCENT -> {
          double rate = couponIssue.getCoupon().getAmount() / 100.0;  // 정수 → 실수 변환
          discount = (int) Math.floor((Cost * rate) / 100) * 100; // 백원 단위 절삭
        }
      }

      // 사용 가능 여부 및 할인 금액 반환
      // TODO : dto 등을 통한 사용 가능 여부, 원인, 할인 금액 등 전송 필요
      return discount;
    }

    return -1;
  }

  // 쿠폰 사용 확정 메서드
  public void useCoupon(Long couponId, Long userId) {
    // 쿠폰 사용 처리 (쿠폰 비활성화)

    // Coupon 비활성화

    // 완료 메시지
  }
}
