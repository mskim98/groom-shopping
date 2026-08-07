package groom.backend.domain.coupon.service;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.infrastructure.kafka.stream.CouponDelayEvent;
import groom.backend.infrastructure.kafka.stream.CouponDelayProducer;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponCommonService {
  private final CouponRepository couponRepository;
  private final CouponDelayProducer couponDelayProducer;
  private final CouponStockRedisRepository couponStockRedisRepository;


  @Transactional
  public CouponResponse createCoupon(CouponCreateRequest couponCreateRequest) {
    // dto를 entity로 변환
    Coupon coupon = couponCreateRequest.toEntity();

    // 쿠폰 생성
    Coupon savedCoupon = couponRepository.save(coupon);
    warmUpStockAfterCommit(savedCoupon);
    return CouponResponse.from(savedCoupon);
  }

  @Transactional
  public CouponResponse createCoupon(CouponCreateRequest couponCreateRequest, LocalDateTime expirationTime) {
    // dto를 entity로 변환
    Coupon coupon = couponCreateRequest.toEntity();

    // 쿠폰 생성
    Coupon savedCoupon = couponRepository.save(coupon);

    Long expirationMilis = Duration.between(LocalDateTime.now(), expirationTime).toMillis();
    CouponDelayEvent couponDelayEvent = new CouponDelayEvent(savedCoupon.getId(), expirationMilis, LocalDateTime.now().atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli());
    couponDelayProducer.publishCouponDelayEvent(couponDelayEvent);
    warmUpStockAfterCommit(savedCoupon);
    return CouponResponse.from(savedCoupon);
  }

  // 생성된 쿠폰의 재고를 Redis 에 심어 첫 발급 요청이 DB 비관적 락 폴백을 타지 않게 한다.
  // 폴백은 Redis 와 DB 두 저장소에 걸친 구간이라 분산 락을 오래 잡고, 이벤트 시작 순간이
  // 곧 최대 트래픽 시점이라 하필 그때 다수 요청이 동시에 폴백으로 몰린다.
  //
  // 커밋 이후에 심는 이유 : 커밋 전에 심으면 생성 트랜잭션이 롤백됐을 때
  // 존재하지 않는 쿠폰의 재고가 Redis 에 남아, 발급 요청이 Lua 차감까지 성공한 뒤
  // DB 영속화 단계에서 COUPON_NOT_FOUND 로 떨어진다.
  private void warmUpStockAfterCommit(Coupon coupon) {
    if (coupon.getQuantity() == null || coupon.getQuantity() <= 0) {
      return;
    }
    Long couponId = coupon.getId();
    Long quantity = coupon.getQuantity();

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      couponStockRedisRepository.initStock(couponId, quantity);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        couponStockRedisRepository.initStock(couponId, quantity);
      }
    });
  }

  public CouponResponse findCoupon(Long couponId) {
    // 단일 쿠폰 검색
    Coupon coupon = couponRepository.findById(couponId).orElseThrow(
            ()-> new BusinessException(ErrorCode.COUPON_NOT_FOUND)
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
            ()-> new BusinessException(ErrorCode.COUPON_NOT_FOUND)
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
