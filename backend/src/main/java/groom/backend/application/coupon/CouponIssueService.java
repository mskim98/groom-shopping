package groom.backend.application.coupon;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.coupon.mapper.CouponContextMapper;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.entity.CouponIssue;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.model.vo.DiscountContext;
import groom.backend.domain.coupon.policy.DiscountAmountMultiPolicy;
import groom.backend.domain.coupon.policy.DiscountMultiPolicy;
import groom.backend.domain.coupon.policy.DiscountPercentMultiPolicy;
import groom.backend.domain.coupon.policy.DiscountPolicy;
import groom.backend.domain.coupon.policy.DiscountPolicyFactory;
import groom.backend.domain.coupon.policy.DiscountSinglePolicy;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자에게 발급된 쿠폰을 관리하는 서비스 (캐싱 적용 리팩토링)
 */
// @Service : 비즈니스 로직을 담는 계층임을 스프링에 알려 빈으로 등록한다.
@Service
// @RequiredArgsConstructor : final 필드를 받는 생성자를 만들어 생성자 기반 의존성 주입을 한다.
@RequiredArgsConstructor
// @Transactional(readOnly = true) : 클래스 기본값을 '읽기 전용 트랜잭션'으로 둔다.
// 조회 메서드는 그대로 두고, 데이터를 바꾸는 메서드에만 @Transactional 을 따로 붙여 쓰기를 허용한다.
@Transactional(readOnly = true)
// @Slf4j : Lombok이 log 객체를 생성한다.
@Slf4j
public class CouponIssueService {

    // --- 캐시 이름 및 접두사 상수 ---
    public static final String COUPON_ITEM_CACHE_NAME = "coupon-item-cache";
    public static final String COUPON_LIST_CACHE_NAME = "user-coupons-list-cache";
    // 현재 다른 도메인과 같은 레디스를 공유하므로, 키 접두사(coupon-item-cache::)를 붙여 도메인 구분
    public static final String CACHE_PREFIX = COUPON_ITEM_CACHE_NAME + "::";

    // 분산 락 설정
    // - 쿠폰별 락 키로 분리해, 서로 다른 쿠폰 간 경합을 제거
    // - waitTime=3초: 락 대기 한도. 초과 시 빠른 실패(무한 대기 방지)
    // - leaseTime=5초: 락이 자동 해제되는 TTL. 임계 구역이 짧기 때문에 충분
    private static final String COUPON_LOCK_KEY_PREFIX = "coupon:lock:";
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 5L;

    // private final 의존성들 : 스프링이 생성자로 주입(제어의 역전)하고,
    // 외부에서 바꿀 수 없게 막아(private final) 서비스가 항상 같은 협력 객체를 안전하게 쓰도록 한다.
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final DiscountPolicyFactory discountPolicyFactory;
    private final CacheManager couponCacheManager; // 스프링 Cache 추상화 진입점
    private final RedisTemplate<String, CouponIssueResponse> couponCacheTemplate; // 쿠폰 DTO 캐시 직접 조작용
    private final RedissonClient redissonClient; // 분산 락(다중 서버에서 동시성 제어)용
    private final CouponStockRedisRepository couponStockRedisRepository; // Redis 재고 Lua 연산 담당
    // 자기호출 우회: persistIssuedCoupon / issueCouponInDbOnly 의 @Transactional 이
    // issueCoupon() 내부 직접 호출로 무효화되지 않도록 프록시를 거쳐 호출한다.
    private final ObjectProvider<CouponIssueService> selfProvider;


    /**
     * 선착순 쿠폰 발급.
     * <p>
     * 동시성 제어 방식: Redisson 분산 락(쿠폰별 키, {@code coupon:lock:{couponId}})을 {@code tryLock}으로 획득 대기 한도
     * {@value #LOCK_WAIT_SECONDS}초, 자동 해제 {@value #LOCK_LEASE_SECONDS}초 다중 인스턴스 환경에서도 그대로 동시성이 보장 락 구간 안에서
     * {@link CouponStockRedisRepository#tryIssue} Lua 스크립트가 "수량 확인 → 중복 체크 → 수량 차감" 을 단일 원자 연산으로 수행 DB에 영속화:
     * {@link CouponIssue} 저장 + {@link Coupon#decreaseQuantity()} 로 수량 동기화 DB 저장이 실패하면 Redis 재고를 롤백
     * <p>
     * <p>
     * 기존 {@code findByIdForUpdate} (JPA 비관적 락) 방식 대비 DB 커넥션 점유 시간이 사라져 트래픽 급증 시 커넥션 풀 고갈을 방지
     */
    // @CacheEvict : 발급에 성공하면 그 사용자의 '쿠폰 목록 캐시'를 지워(무효화)
    // 다음 조회 때 새로 발급된 쿠폰이 반드시 보이도록 한다(캐시-DB 불일치 방지).
    // propagation = NOT_SUPPORTED : 클래스 기본값 @Transactional(readOnly=true) 를 덮어써
    // 이 메서드를 '비트랜잭션'으로 실행한다. Redisson 락 + Lua 실행 구간 동안 DB 커넥션을
    // 점유하지 않고(커넥션 풀 고갈 방지), 위임 메서드의 REQUIRES_NEW 가 readOnly 트랜잭션에
    // 합류(REQUIRED)해 쿠폰 차감 쓰기가 유실되는 일을 막는다.
    @CacheEvict(cacheNames = COUPON_LIST_CACHE_NAME, key = "#user.id")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CouponIssueResponse issueCoupon(Long couponId, User user) {
        // 쿠폰별 락 객체 획득. 키가 쿠폰마다 다르므로 다른 쿠폰끼리는 서로 막지 않는다.
        RLock lock = redissonClient.getLock(COUPON_LOCK_KEY_PREFIX + couponId);
        boolean acquired = false;
        try {
            // tryLock : 최대 waitTime 동안만 락을 기다리고, 잡으면 leaseTime 뒤 자동 해제(데드락 방지).
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                // 대기 한도를 넘으면 무한 대기 대신 빠른 실패로 떨어뜨린다.
                log.warn("[COUPON_LOCK_TIMEOUT] couponId={}, userId={}", couponId, user.getId());
                throw new BusinessException(ErrorCode.COUPON_OUT_OF_STOCK);
            }

            // Lua 원자 연산: 재고 확인 + 중복 확인 + 차감
            CouponStockRedisRepository.IssueResult issueResult =
                    couponStockRedisRepository.tryIssue(couponId, user.getId());

            switch (issueResult) {
                case OUT_OF_STOCK -> throw new BusinessException(ErrorCode.COUPON_OUT_OF_STOCK);
                case ALREADY_ISSUED -> throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
                case NOT_INITIALIZED -> {
                    // Redis에 재고가 없으면 DB에서 초기화 후 재시도 대신, DB 기반 폴백으로 발급
                    log.warn("[COUPON_STOCK_FALLBACK] Redis stock not initialized, falling back to DB. couponId={}",
                            couponId);
                    return selfProvider.getObject().issueCouponInDbOnly(couponId, user);
                }
                default -> {
                    // SUCCESS - DB 영속화 진행
                }
            }

            try {
                return selfProvider.getObject().persistIssuedCoupon(couponId, user);
            } catch (RuntimeException dbError) {
                // DB 저장 실패 → Redis 재고/발급자 SET 롤백으로 상태 일치 유지
                couponStockRedisRepository.rollbackIssue(couponId, user.getId());
                throw dbError;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.COUPON_OUT_OF_STOCK);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Redis 성공 이후 DB 영속화. 트랜잭션 경계시작
     */
    // propagation = REQUIRES_NEW : 항상 독립된 read-write 새 트랜잭션으로 DB 영속화를 묶는다.
    // ✅ 자기호출 함정 해결: issueCoupon() 이 selfProvider.getObject() 로 프록시를 거쳐 호출.
    // ✅ readOnly 합류 방지: REQUIRES_NEW 라 바깥 트랜잭션 유무와 무관하게 쓰기 트랜잭션이 보장된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CouponIssueResponse persistIssuedCoupon(Long couponId, User user) {
        Coupon coupon = couponRepository.findById(couponId).orElseThrow(
                () -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        if (!coupon.getIsActive()) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }

        coupon.decreaseQuantity();

        CouponIssue couponIssue = couponIssueRepository.save(CouponIssue.builder()
                .coupon(coupon)
                .userId(user.getId())
                .createdAt(LocalDateTime.now())
                .deletedAt(LocalDateTime.of(coupon.getExpireDate(), LocalTime.MIN))
                .build());
        couponRepository.save(coupon);

        CouponIssueResponse responseDto = CouponIssueResponse.from(couponIssue);
        Cache couponItemCache = couponCacheManager.getCache(COUPON_ITEM_CACHE_NAME);
        if (couponItemCache != null) {
            couponItemCache.put(responseDto.getCouponIssueId(), responseDto);
        }
        return responseDto;
    }

    /**
     * Redis 재고가 없을 때의 폴백. DB 비관적 락으로 기존 로직을 유지
     */
    // propagation = REQUIRES_NEW : DB 비관적 락 + 발급 저장을 항상 독립된 쓰기 트랜잭션으로 묶는다.
    // ✅ 자기호출 함정 해결: issueCoupon() 이 selfProvider.getObject() 로 프록시를 거쳐 호출.
    // ✅ readOnly 합류 방지: REQUIRES_NEW 라 비관적 락(FOR UPDATE)이 정상 동작하는 쓰기 트랜잭션 보장.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CouponIssueResponse issueCouponInDbOnly(Long couponId, User user) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId).orElseThrow(
                () -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        if (!coupon.getIsActive()) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }
        if (coupon.getQuantity() <= 0) {
            throw new BusinessException(ErrorCode.COUPON_OUT_OF_STOCK);
        }
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

        // DB 커밋 후 Redis 재고를 초기화하고 발급자 기록을 남겨 다음 요청부터는 Redis 경로를 사용
        couponStockRedisRepository.initStock(couponId, coupon.getQuantity());

        CouponIssueResponse responseDto = CouponIssueResponse.from(couponIssue);
        Cache couponItemCache = couponCacheManager.getCache(COUPON_ITEM_CACHE_NAME);
        if (couponItemCache != null) {
            couponItemCache.put(responseDto.getCouponIssueId(), responseDto);
        }
        return responseDto;
    }

    /**
     * 사용자의 쿠폰 '목록' 조회 목록 캐시(@Cacheable)와 함께, 조회된 단건들을 수동으로 단건 캐시('coupon-item-cache')에 저장 (Cache Warming)
     */
    // @Cacheable : 같은 userId 로 다시 호출되면 메서드를 실행하지 않고 캐시 값을 바로 반환한다.
    // (DB 부하를 줄이는 핵심. 데이터가 바뀌면 위의 @CacheEvict 가 캐시를 비워준다)
    @Cacheable(cacheNames = COUPON_LIST_CACHE_NAME, key = "#userId")
    public List<CouponIssueResponse> searchMyCoupon(Long userId) {
        List<CouponIssue> issues = couponIssueRepository.findByUserIdAndIsActiveTrueAndDeletedAtAfter(userId,
                LocalDateTime.now());

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
            // MSET 적용 시 TTL 지정 불가능
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
     * [단건] 쿠폰 사용을 위한 할인 금액 조회 (수정) 이 메서드는 이제 다건 조회 메서드를 재사용
     *
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
     * [다건] 쿠폰 사용을 위한 할인 금액 조회 (Redis MGET/MSET 적용) (수정) couponIdList -> couponIssueIdList로 변경
     *
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
        for (Long requestedId : couponIssueIdList) {
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

            if (discountPolicy instanceof DiscountSinglePolicy) {
                throw new BusinessException(ErrorCode.COUPON_INVALID_POLICY, "단일 사용 전용 쿠폰은 여러 개 사용할 수 없습니다.");
            } else if (discountPolicy instanceof DiscountAmountMultiPolicy) {
                amount.add(context);
            } else if (discountPolicy instanceof DiscountPercentMultiPolicy) {
                percent.add(context);
            }
        }

        // 6. 최종 할인액 계산
        DiscountMultiPolicy discountPercentMultiStrategy = discountPolicyFactory.getDiscountMultiStrategy(
                CouponType.PERCENT);
        DiscountMultiPolicy discountAmountMultiStrategy = discountPolicyFactory.getDiscountMultiStrategy(
                CouponType.DISCOUNT);

        return Math.min(cost, discountPercentMultiStrategy.calculateMultiDiscount(percent)
                + discountAmountMultiStrategy.calculateMultiDiscount(amount));
    }

    /**
     * 쿠폰 사용 확정 메서드 (수정) couponId -> couponIssueId로 파라미터명 변경, @CacheEvict 키 수정
     */
    // @Caching : 여러 개의 캐시 어노테이션을 한 메서드에 함께 적용할 때 사용한다.
    // 쿠폰을 사용하면 단건 캐시와 목록 캐시 둘 다 비워야 옛 데이터가 남지 않는다.
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
        if (!issue.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.COUPON_USER_MATCH_FAILED);
        }
        if (!issue.getIsActive()) {
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE);
        }
        // 쿠폰 만료일 확인
        if (issue.getDeletedAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED);
        }
    }

    /**
     * 쿠폰 검증 (DTO) - 오버로딩
     */
    private void checkCouponUsable(CouponIssueResponse dto, Long userId) {
        // 사용자 확인
        if (!dto.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.COUPON_USER_MATCH_FAILED);
        }
        // 활성화 여부 확인
        if (!dto.getIsActive()) {
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE);
        }
        // 쿠폰 만료일 확인
        if (dto.getDeletedAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED);
        }
    }
}