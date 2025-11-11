package groom.backend.domain.coupon.service;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import groom.backend.interfaces.coupon.dto.request.CouponSearchCondition;
import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
import groom.backend.interfaces.coupon.dto.response.CouponResponse;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponCommonService {
  private final CouponRepository couponRepository;


  @Transactional
  public CouponResponse createCoupon(CouponCreateRequest couponCreateRequest) {
    // dto를 entity로 변환
    Coupon coupon = couponCreateRequest.toEntity();

    // 쿠폰 생성
    Coupon savedCoupon = couponRepository.save(coupon);
    return CouponResponse.from(savedCoupon);
  }

  public CouponResponse findCoupon(Long couponId) {
    // 단일 쿠폰 검색
    Coupon coupon = couponRepository.findById(couponId).orElseThrow(
            ()-> new BusinessException(ErrorCode.NOT_FOUND)
    );

    // 검색 결과 반환
    return CouponResponse.from(coupon);
  }

  public Page<CouponResponse> searchCoupon(CouponSearchCondition condition, Pageable pageable) {
    // 조건부 검색
    // 페이징
    return couponRepository.searchByCondition(condition, pageable)
            .map(CouponResponse::from);
  }

  @Transactional
  public CouponResponse updateCoupon(Long couponId, CouponUpdateRequest couponUpdateRequest) {
    // coupon id 기반 조회
    Coupon currentCoupon = couponRepository.findById(couponId).orElseThrow(
            ()-> new BusinessException(ErrorCode.NOT_FOUND)
    );

    // 쿠폰 수정
    currentCoupon.update(couponUpdateRequest);

    // 정책 고정, 이름과 설명, 수량과 만료 기간만 바꿀 수 있음. -> 정책에 따라 변경 가능할 것으로 보임.
    // 결제 관련된 내용이라 생성 후에는 고정되도록 하는 것이 맞는 것 같습니다.
    couponRepository.save(currentCoupon);

    // 수정 결과 반환
    return CouponResponse.from(currentCoupon);
  }

  @Transactional
  public Boolean deleteCoupon(Long couponId) {
    // 쿠폰 존재 여부 확인
    if (!couponRepository.existsById(couponId)) {
      return false;
    }
    // 쿠폰 삭제
    couponRepository.deleteById(couponId);
    return true;
  }
}
