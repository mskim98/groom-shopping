package groom.backend.application.coupon;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.coupon.mapper.CouponContextMapper;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.entity.CouponIssue;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.model.vo.DiscountContext;
import groom.backend.domain.coupon.policy.*;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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

  // 쿠폰 발급 메서드
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

    // 사용자 중복 쿠폰 발급 방지
    if (!couponIssueRepository.findByCouponIdAndUserId(couponId, user.getId()).isEmpty()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 발급받은 쿠폰입니다.");
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

  // 사용자 쿠폰 조회 메서드
  public List<CouponIssueResponse> searchMyCoupon(Long userId) {
    // 유저 id로 쿠폰 조회
    // 현재 사용 가능한 (만료 시간, 활성화 여부) 쿠폰 조회
    return couponIssueRepository.findByUserIdAndIsActiveTrueAndDeletedAtAfter(userId, LocalDateTime.now()).stream().map(CouponIssueResponse::from).collect(Collectors.toList());
  }

  /**
   * 쿠폰 사용을 위한 할인 금액 조회
   * 사용 가능 여부 반환 시 사용하지 못할 경우 exception 던짐
   * 쿠폰 미조회 : Not Found
   * 정책적 검증 실패 (사용자 불일치, 사용기간 만료 등) : Forbidden
   * @param couponId
   * @param userId
   * @param cost
   * @return
   */

  public Integer calculateDiscount(Long couponId, Long userId, Integer cost) {
    Integer discount = 0;

    // 쿠폰 조회
    CouponIssue couponIssue = couponIssueRepository.findByCouponIdAndUserId(couponId, userId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "쿠폰이 존재하지 않습니다."));

    // 쿠폰 검증
    checkCouponUsable(couponIssue, userId);

    DiscountPolicy discountPolicy = DiscountPolicyFactory.getDiscountStrategy(couponIssue.getCoupon().getType());
    DiscountContext context = CouponContextMapper.from(couponIssue.getCoupon(), cost);

    // 할인율 계산 로직
    return discountPolicy.calculateDiscount(context);
  }

  public Integer calculateDiscount(List<Long> couponIdList, Long userId, Integer cost) {
    // couponId를 in 쿼리로 조회
    List<CouponIssue> couponIssueList = couponIssueRepository.findByCouponIdInAndUserId(couponIdList, userId);

    // 정액 할인
    List<DiscountContext> amount = new ArrayList<>();
    // 비율 할인
    List<DiscountContext> percent = new ArrayList<>();

    for(CouponIssue couponIssue : couponIssueList) {
      // 쿠폰 검증
      DiscountContext context = CouponContextMapper.from(couponIssue.getCoupon(), cost);

      checkCouponUsable(couponIssue, userId);
      DiscountPolicy discountPolicy = DiscountPolicyFactory.getDiscountStrategy(couponIssue.getCoupon().getType());
      // 단일 쿠폰인지 검증, 하나라도 단일 사용 전용 쿠폰 존재 시 실패
      if(discountPolicy instanceof DiscountSinglePolicy)
        throw new BusinessException(ErrorCode.INVALID_PARAMETER, "단일 사용 전용 쿠폰은 여러 개 사용할 수 없습니다.");
      else if (discountPolicy instanceof DiscountAmountMultiPolicy)
        amount.add(context);
      else if (discountPolicy instanceof DiscountPercentMultiPolicy)
        percent.add(context);
    }

    DiscountMultiPolicy discountPercentMultiStrategy = DiscountPolicyFactory.getDiscountMultiStrategy(CouponType.DISCOUNT);
    DiscountMultiPolicy discountAmountMultiStrategy = DiscountPolicyFactory.getDiscountMultiStrategy(CouponType.PERCENT);

    return discountPercentMultiStrategy.calculateMultiDiscount(percent) + discountAmountMultiStrategy.calculateMultiDiscount(amount);
  }

  // 쿠폰 사용 확정 메서드
  // 사용 시 실패할 경우 exception 던짐
  // 쿠폰 미조회 : Not Found
  // 정책적 검증 실패 (사용자 불일치, 사용기간 만료 등) : Forbidden
  @Transactional
  public Boolean useCoupon(Long couponId, Long userId) {
    // 쿠폰 사용 처리 (쿠폰 비활성화)
    // 쿠폰 조회
    CouponIssue issue = couponIssueRepository.findById(couponId).orElseThrow(
            () -> new BusinessException(ErrorCode.NOT_FOUND, "쿠폰이 존재하지 않습니다."));

    // 쿠폰 검증
    checkCouponUsable(issue, userId);

    // Coupon 비활성화
    issue.setIsActive(false);
    issue.setDeletedAt(LocalDateTime.now());
    couponIssueRepository.save(issue);

    // 완료 메시지
    return true;
  }

  // 쿠폰 검증 메서드
  // 할인금액 계산, 사용 확정에서 사용
  private void checkCouponUsable(CouponIssue issue, Long userId) {
    // 사용자 확인, 활성화 여부 확인
    if (!issue.getUserId().equals(userId))
      throw new BusinessException(ErrorCode.FORBIDDEN, "쿠폰 소유자와 사용자가 일치하지 않습니다.");
    if (!issue.getIsActive())
      throw new BusinessException(ErrorCode.FORBIDDEN, "쿠폰 소유자와 사용자가 일치하지 않습니다.");
    // 쿠폰 만료일 확인
    // isActive가 true일 경우 활성화된 상태이기에 만료일을 검사하지 않음. DeletedAt을 검사해주어야 한다.
    if (issue.getDeletedAt().isBefore(LocalDateTime.now()))
      throw new BusinessException(ErrorCode.FORBIDDEN, "쿠폰 사용일이 만료되었습니다.");
  }
}
