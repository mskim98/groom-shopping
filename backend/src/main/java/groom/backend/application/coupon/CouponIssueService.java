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
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 사용자에게 발급된 쿠폰을 관리하는 서비스 (캐싱 적용 리팩토링)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class CouponIssueService {

  // --- 캐시 이름 및 접두사 상수 ---
  public static final String COUPON_ITEM_CACHE_NAME = "coupon-item-cache";
  public static final String COUPON_LIST_CACHE_NAME = "user-coupons-list-cache";
  // 현재 다른 도메인과 같은 레디스를 공유하므로, 키 접두사(coupon-item-cache::)를 붙여 도메인 구분
  public static final String CACHE_PREFIX = COUPON_ITEM_CACHE_NAME + "::";


  private final CouponRepository couponRepository;
  private final CouponIssueRepository couponIssueRepository;
  private final DiscountPolicyFactory discountPolicyFactory;
  private final CacheManager couponCacheManager; // CacheManager 주입

  private final RedisTemplate<String, CouponIssueResponse> couponCacheTemplate;


  /**
   * 쿠폰 발급 메서드
   * 발급 성공 시, 단건 쿠폰 캐시(coupon-item-cache)에 즉시 저장합니다. (Cache Put)
   * 만약 새 쿠폰을 발급할 경우, 레디스에 저장된 캐시 목록은 삭제됩니다. (Cache Evict)
   */
  @Transactional
  @CacheEvict(cacheNames = COUPON_LIST_CACHE_NAME, key = "#user.id")
  public CouponIssueResponse issueCoupon(Long couponId, User user) {
    // 쿠폰 조회 (비관적 락)
    Coupon coupon = couponRepository.findByIdForUpdate(couponId).orElseThrow(
            () -> new BusinessException(ErrorCode.COUPON_NOT_FOUND)
    );

    // 활성화 여부 확인
    if (!coupon.getIsActive()) {
      throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
    }

    // 수량 확인
    if (coupon.getQuantity() <= 0) {
      throw new BusinessException(ErrorCode.COUPON_OUT_OF_STOCK);
    }

    // 사용자 중복 쿠폰 발급 방지
    // couponId와 userId로 조회
    if (!couponIssueRepository.findByCouponIdAndUserId(couponId, user.getId()).isEmpty()) {
      throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
    }

    coupon.decreaseQuantity();

    CouponIssue couponIssue = couponIssueRepository.save(CouponIssue.builder()
            .coupon(coupon)
            .userId(user.getId())
            .createdAt(LocalDateTime.now())
            .deletedAt(LocalDateTime.of(coupon.getExpireDate(), LocalTime.MIN))
            .build());
    couponRepository.save(coupon);

    // DTO 변환
    CouponIssueResponse responseDto = CouponIssueResponse.from(couponIssue);

    // --- 캐시 수동 등록 (Cache Put) ---
    // 새로 발급된 쿠폰을 즉시 '단건 캐시'에 저장
    Cache couponItemCache = couponCacheManager.getCache(COUPON_ITEM_CACHE_NAME);
    if (couponItemCache != null) {
      // getCouponIssueId()는 CouponIssue의 PK (Long)를 반환해야 함
      couponItemCache.put(responseDto.getCouponIssueId(), responseDto);
    }

    return responseDto;
  }

  /**
   * 사용자의 쿠폰 '목록' 조회
   * 목록 캐시(@Cacheable)와 함께, 조회된 단건들을 수동으로 단건 캐시('coupon-item-cache')에 저장합니다. (Cache Warming)
   */
  @Cacheable(cacheNames = COUPON_LIST_CACHE_NAME, key = "#userId")
  public List<CouponIssueResponse> searchMyCoupon(Long userId) {
    List<CouponIssue> issues = couponIssueRepository.findByUserIdAndIsActiveTrueAndDeletedAtAfter(userId, LocalDateTime.now());

    Cache couponItemCache = couponCacheManager.getCache(COUPON_ITEM_CACHE_NAME);
    if (couponItemCache == null) {
      // 캐시 설정이 안 되어있을 경우의 방어 코드
      return issues.stream().map(CouponIssueResponse::from).collect(Collectors.toList());
    }

    List<CouponIssueResponse> responses = issues.stream()
            .map(CouponIssueResponse::from)
            .collect(Collectors.toList());

    if (!responses.isEmpty()) {
      Map<String, CouponIssueResponse> cacheWriteMap = new HashMap<>();
      responses.forEach(couponResponse -> {
        cacheWriteMap.put(CACHE_PREFIX + couponResponse.getCouponIssueId(), couponResponse);
      });
      // MSET 적용 시 TTL 지정 불가능.
      for (CouponIssueResponse r : responses) {
        String key = CACHE_PREFIX + r.getCouponIssueId();
        couponCacheTemplate.opsForValue().set(key, r, Duration.ofMinutes(60)); // TTL 적용, 1시간
      }
    }

//    // 목록을 순회하며 개별 쿠폰 캐시에 수동으로 PUT (Cache Warming)
//    responses.forEach(couponResponse -> {
//      couponItemCache.put(couponResponse.getCouponIssueId(), couponResponse);
//    });

    return responses;
  }

  /**
   * [단건] 쿠폰 사용을 위한 할인 금액 조회
   * (수정) 이 메서드는 이제 다건 조회 메서드를 재사용합니다.
   * @param couponIssueId (주의) Coupon.id(템플릿ID)가 아닌 CouponIssue.id(발급쿠폰PK)
   * @param userId
   * @param cost
   * @return
   */
  public Integer calculateDiscount(Long couponIssueId, Long userId, Integer cost) {
    // 다건 할인 계산 메서드를 List.of()로 감싸서 호출
    return calculateDiscount(List.of(couponIssueId), userId, cost);
  }

  /**
   * [다건] 쿠폰 사용을 위한 할인 금액 조회 (Redis MGET/MSET 적용)
   * (수정) couponIdList -> couponIssueIdList로 변경
   * @param couponIssueIdList (주의) Coupon.id(템플릿ID)가 아닌 CouponIssue.id(발급쿠폰PK) 리스트
   * @param userId
   * @param cost
   * @return
   */
  public Integer calculateDiscount(List<Long> couponIssueIdList, Long userId, Integer cost) {

    // 1. 캐시에서 MGET (Multi-Get)
    List<String> cacheKeys = couponIssueIdList.stream()
            .map(id -> CACHE_PREFIX + id)
            .collect(Collectors.toList());

    // MGET 실행 (1번의 Redis 통신)
    List<CouponIssueResponse> cachedList = couponCacheTemplate.opsForValue().multiGet(cacheKeys);

    Map<Long, CouponIssueResponse> couponMap = new HashMap<>(); // (Key: couponIssueId)
    List<Long> cacheMissIds = new ArrayList<>(); // 캐시에 없던 ID 목록

    // 2. 캐시 히트/미스 분리
    for (int i = 0; i < couponIssueIdList.size(); i++) {
      CouponIssueResponse couponDto = cachedList.get(i);
      Long couponIssueId = couponIssueIdList.get(i);

      if (couponDto != null) {
        // Cache Hit
        couponMap.put(couponIssueId, couponDto);
      } else {
        // Cache Miss
        cacheMissIds.add(couponIssueId);
      }
    }

    // 3. 캐시 미스된 ID가 있다면 DB에서 IN 쿼리로 조회
    if (!cacheMissIds.isEmpty()) {
      // (수정) couponIssueId(PK)로 조회. (주의: 여기서는 userId 조건을 넣지 않습니다. 아래 5번에서 검증)
      List<CouponIssue> foundIssues = couponIssueRepository.findAllById(cacheMissIds);

      // 4. DB 조회 결과를 DTO로 변환하고, 캐시에 MSET (Multi-Set)
      Map<String, CouponIssueResponse> cacheWriteMap = new HashMap<>();
      for (CouponIssue issue : foundIssues) {
        CouponIssueResponse dto = CouponIssueResponse.from(issue);

        // 최종 맵에도 추가
        couponMap.put(issue.getId(), dto);

        // 캐시에 쓸 맵에도 추가
        cacheWriteMap.put(CACHE_PREFIX + issue.getId(), dto);
      }

      if (!cacheWriteMap.isEmpty()) {
        // MSET 실행 (1번의 Redis 통신)
        couponCacheTemplate.opsForValue().multiSet(cacheWriteMap);
        // (필요시 TTL 설정 로직 추가)
      }
    }

    // 5. [중요] 비즈니스 로직 수행 (보안 검증 포함)
    List<DiscountContext> amount = new ArrayList<>();
    List<DiscountContext> percent = new ArrayList<>();

    // (couponIssueIdList)를 순회해야 요청한 쿠폰이 DB에 없는 경우를 잡을 수 있음
    for(Long requestedId : couponIssueIdList) {
      CouponIssueResponse couponDto = couponMap.get(requestedId);

      // 5-1. 쿠폰 존재 여부 확인
      if (couponDto == null) {
        throw new BusinessException(ErrorCode.COUPON_NOT_FOUND, "요청한 쿠폰 중 일부를 찾을 수 없습니다: " + requestedId);
      }

      // 5-2. [보안] 쿠폰 검증 (소유주, 만료일, 활성 상태)
      // (캐시된 데이터이므로, 현재 요청의 userId와 '반드시' 비교해야 함)
      checkCouponUsable(couponDto, userId);

      // 5-3. 정책 로직 수행
      DiscountContext context = CouponContextMapper.from(couponDto, cost);
      DiscountPolicy discountPolicy = discountPolicyFactory.getDiscountStrategy(couponDto.getCouponType());

      if (discountPolicy instanceof DiscountSinglePolicy)
        throw new BusinessException(ErrorCode.COUPON_INVALID_POLICY, "단일 사용 전용 쿠폰은 여러 개 사용할 수 없습니다.");
      else if (discountPolicy instanceof DiscountAmountMultiPolicy)
        amount.add(context);
      else if (discountPolicy instanceof DiscountPercentMultiPolicy)
        percent.add(context);
    }

    // 6. 최종 할인액 계산
    DiscountMultiPolicy discountPercentMultiStrategy = discountPolicyFactory.getDiscountMultiStrategy(CouponType.PERCENT);
    DiscountMultiPolicy discountAmountMultiStrategy = discountPolicyFactory.getDiscountMultiStrategy(CouponType.DISCOUNT);

    return Math.min(cost, discountPercentMultiStrategy.calculateMultiDiscount(percent) + discountAmountMultiStrategy.calculateMultiDiscount(amount));
  }

  /**
   * 쿠폰 사용 확정 메서드
   * (수정) couponId -> couponIssueId로 파라미터명 변경, @CacheEvict 키 수정
   */
  @Caching(evict = {
          // 1. 단건 쿠폰 캐시에서 이 쿠폰을 제거 (키 이름 수정)
          @CacheEvict(cacheNames = COUPON_ITEM_CACHE_NAME, key = "#couponIssueId"),
          // 2. 이 쿠폰이 포함된 '사용자 목록' 캐시도 제거
          @CacheEvict(cacheNames = COUPON_LIST_CACHE_NAME, key = "#userId")
  })
  @Transactional
  public Boolean useCoupon(Long couponIssueId, Long userId) {
    // 쿠폰 조회 (CouponIssue의 PK로 조회)
    CouponIssue issue = couponIssueRepository.findById(couponIssueId).orElseThrow(
            () -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

    // 쿠폰 검증 (Entity 사용)
    checkCouponUsable(issue, userId);

    // Coupon 비활성화 (사용 처리)
    issue.setIsActive(false);
    issue.setDeletedAt(LocalDateTime.now());
    couponIssueRepository.save(issue); // @Transactional이므로 사실 save는 필요 없을 수 있음

    return true;
  }

  // --- 쿠폰 검증 헬퍼 메서드 ---

  /**
   * 쿠폰 검증 (Entity)
   */
  private void checkCouponUsable(CouponIssue issue, Long userId) {
    // 사용자 확인, 활성화 여부 확인
    if (!issue.getUserId().equals(userId))
      throw new BusinessException(ErrorCode.COUPON_USER_MATCH_FAILED);
    if (!issue.getIsActive())
      throw new BusinessException(ErrorCode.COUPON_NOT_USABLE);
    // 쿠폰 만료일 확인
    if (issue.getDeletedAt().isBefore(LocalDateTime.now()))
      throw new BusinessException(ErrorCode.COUPON_EXPIRED);
  }

  /**
   * 쿠폰 검증 (DTO) - 오버로딩
   */
  private void checkCouponUsable(CouponIssueResponse dto, Long userId) {
    // 사용자 확인
    if (!dto.getUserId().equals(userId))
      throw new BusinessException(ErrorCode.COUPON_USER_MATCH_FAILED);
    // 활성화 여부 확인
    if (!dto.getIsActive())
      throw new BusinessException(ErrorCode.COUPON_NOT_USABLE);
    // 쿠폰 만료일 확인
    if (dto.getDeletedAt().isBefore(LocalDateTime.now()))
      throw new BusinessException(ErrorCode.COUPON_EXPIRED);
  }
}