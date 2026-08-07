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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    // RedisConfig 의 coupon-item-cache TTL(1시간)과 맞춘다
    private static final Duration ITEM_CACHE_TTL = Duration.ofHours(1);

    // 임계 구역 안의 DB 작업 상한(초).
    //
    // 락이 사라진 지금 이 상한이 지키는 것은 락 보유 시간이 아니라 파티션 선두 차단이다.
    // 컨슈머는 파티션당 1명이라 한 요청의 DB 작업이 느려지면 그 뒤 전 요청이 함께 밀린다.
    // 상한이 없으면 느려진 DB 작업 하나가 그 쿠폰의 발급을 무한정 멈춘다.
    //
    // 다만 이 값이 끊어주는 것은 쿼리 실행 구간뿐이다. 커넥션 획득 대기는 Hikari 의
    // connection-timeout(설정하지 않아 기본 30초) 소관이라, 풀이 고갈되면 이 상한보다 오래 잡힐 수 있다.
    private static final int DB_WORK_TIMEOUT_SECONDS = 3;

    // private final 의존성들 : 스프링이 생성자로 주입(제어의 역전)하고,
    // 외부에서 바꿀 수 없게 막아(private final) 서비스가 항상 같은 협력 객체를 안전하게 쓰도록 한다.
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final DiscountPolicyFactory discountPolicyFactory;
    private final CacheManager couponCacheManager; // 스프링 Cache 추상화 진입점
    private final RedisTemplate<String, CouponIssueResponse> couponCacheTemplate; // 쿠폰 DTO 캐시 직접 조작용
    private final CouponStockRedisRepository couponStockRedisRepository; // Redis 재고 Lua 연산 담당
    // 자기호출 우회: confirmIssue / persistIssuedCoupon / issueCouponInDbOnly 의 @Transactional 이
    // 내부 직접 호출로 무효화되지 않도록 프록시를 거쳐 호출한다.
    private final ObjectProvider<CouponIssueService> selfProvider;



    /**
     * 게이트를 다시 돌리지 않고 DB 확정만 한다. 접수 단계에서 Lua 게이트를 이미 통과한 요청 전용
     *
     * <p>컨슈머에서 {@code tryIssue} 를 또 호출하면 같은 요청이 재고를 두 번 깎아
     * 실제 발급 가능 수량보다 빨리 마감된다
     */
    // @CacheEvict : 발급에 성공하면 그 사용자의 쿠폰 목록 캐시를 비운다
    // 없으면 발급 직후 /coupon/me 가 옛 목록을 돌려준다
    // propagation = NOT_SUPPORTED : Lua 롤백 구간이 DB 커넥션을 물지 않게 한다
    @CacheEvict(cacheNames = COUPON_LIST_CACHE_NAME, key = "#user.id")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CouponIssueResponse confirmIssue(Long couponId, User user) {
        try {
            return selfProvider.getObject().persistIssuedCoupon(couponId, user);
        } catch (BusinessException be) {
            if (be.getErrorCode() == ErrorCode.COUPON_ALREADY_ISSUED) {
                // 이 사용자는 실제로 쿠폰을 갖고 있다 - 발급자 SET 은 남기고 재고만 되돌린다
                couponStockRedisRepository.rollbackStockOnly(couponId);
            } else {
                couponStockRedisRepository.rollbackIssue(couponId, user.getId());
            }
            throw be;
        } catch (RuntimeException dbError) {
            // DB 저장 실패 → Redis 재고/발급자 SET 롤백으로 상태 일치 유지
            couponStockRedisRepository.rollbackIssue(couponId, user.getId());
            throw dbError;
        }
    }

    /**
     * Redis 성공 이후 DB 영속화. 트랜잭션 경계시작
     */
    // propagation = REQUIRES_NEW : 항상 독립된 read-write 새 트랜잭션으로 DB 영속화를 묶는다.
    // ✅ 자기호출 함정 해결: confirmIssue() 가 selfProvider.getObject() 로 프록시를 거쳐 호출.
    // ✅ readOnly 합류 방지: REQUIRES_NEW 라 바깥 트랜잭션 유무와 무관하게 쓰기 트랜잭션이 보장된다.
    // timeout : 이 구간은 파티션당 1명인 컨슈머 위에서 돈다. 상한이 없으면 DB 가 느려질 때
    // 그 쿠폰의 뒤 요청 전부가 함께 멈춘다. 상한을 넘으면 예외로 끊어 다음 요청으로 넘어간다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = DB_WORK_TIMEOUT_SECONDS)
    public CouponIssueResponse persistIssuedCoupon(Long couponId, User user) {
        Coupon coupon = couponRepository.findById(couponId).orElseThrow(
                () -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        if (!coupon.getIsActive()) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }

        CouponIssue couponIssue;
        try {
            // saveAndFlush : 제약 위반을 커밋 시점이 아니라 여기서 터뜨려 도메인 예외로 바꾼다
            couponIssue = couponIssueRepository.saveAndFlush(CouponIssue.builder()
                    .coupon(coupon)
                    .userId(user.getId())
                    .createdAt(LocalDateTime.now())
                    .deletedAt(LocalDateTime.of(coupon.getExpireDate(), LocalTime.MIN))
                    .build());
        } catch (DataIntegrityViolationException e) {
            // uq_coupon_issue_user 위반 - Redis 발급자 SET 이 유실됐어도 여기서 막힌다
            log.warn("[COUPON_DUPLICATE_BLOCKED_BY_DB] couponId={}, userId={}", couponId, user.getId());
            throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
        }

        // DTO 는 감산 UPDATE 전에 만든다
        // clearAutomatically 로 영속성 컨텍스트가 비워진 뒤 엔티티를 읽으면 지연 로딩이 깨진다
        CouponIssueResponse responseDto = CouponIssueResponse.from(couponIssue);

        // 읽고-빼는 대신 DB 가 직접 감산한다 (lost update 창 제거)
        // 동기 경로는 분산 락, 비동기 경로는 파티션 직렬 소비로 각각 배타 제어를 받지만
        // 두 경로가 서로를 배제하지는 않으므로, 수량 감산 자체가 원자적이어야 한다
        if (couponRepository.decreaseQuantityAtomically(couponId) == 0) {
            // Redis 는 통과시켰는데 DB 수량이 이미 0 이면 두 저장소가 어긋난 상태다
            // 예외로 끊으면 호출부의 rollbackIssue 가 Redis 재고를 되돌려 낮은 쪽으로 정합을 맞춘다
            log.warn("[COUPON_DB_STOCK_EXHAUSTED] couponId={}, userId={}", couponId, user.getId());
            throw new BusinessException(ErrorCode.COUPON_STORE_MISMATCH);
        }
        writeItemCache(responseDto);
        return responseDto;
    }

    /**
     * Redis 재고가 없을 때({@code NOT_INITIALIZED})의 폴백. DB 비관적 락으로 기존 로직을 유지
     *
     * <p>재고 키가 없어 게이트가 판정하지 못한 요청({@code gateReserved=false})이 여기로 온다
     * DB 수량을 근거로 Redis 재고를 되쓰는 유일한 지점이라, 재고 키가 없는 상황 외에는 진입하지 않아야 한다
     */
    // @CacheEvict : 이 경로로 발급돼도 목록 캐시가 비워져야 한다
    // propagation = REQUIRES_NEW : DB 비관적 락 + 발급 저장을 항상 독립된 쓰기 트랜잭션으로 묶는다.
    // ✅ 자기호출 함정 해결: 호출부가 selfProvider.getObject() 로 프록시를 거쳐 호출.
    // ✅ readOnly 합류 방지: REQUIRES_NEW 라 비관적 락(FOR UPDATE)이 정상 동작하는 쓰기 트랜잭션 보장.
    // timeout : DB 비관적 락을 잡는 가장 긴 구간이다. 워밍업(CouponStockWarmUpRunner,
    // CouponCommonService.createCoupon)으로 정상 운영에서는 진입하지 않지만, 진입했을 때
    // 파티션 선두가 무한정 막히지 않도록 상한을 건다.
    @CacheEvict(cacheNames = COUPON_LIST_CACHE_NAME, key = "#user.id")
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = DB_WORK_TIMEOUT_SECONDS)
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

        // 다음 요청부터 Redis 경로를 타도록 재고를 심는다. 반드시 커밋 이후여야 한다 -
        // 이 트랜잭션에는 timeout 이 걸려 있어 flush·commit 지연으로 롤백될 수 있는데,
        // 커밋 전에 심으면 롤백된 차감이 Redis 에만 남아 재고가 DB 보다 적어진다.
        long remainingQuantity = coupon.getQuantity();
        runAfterCommit(() -> couponStockRedisRepository.initStock(couponId, remainingQuantity));

        CouponIssueResponse responseDto = CouponIssueResponse.from(couponIssue);
        writeItemCache(responseDto);
        return responseDto;
    }

    /**
     * 단건 캐시 쓰기. 읽기 경로({@link #calculateDiscount})가 {@code couponCacheTemplate} 하나뿐이므로 쓰기도 같은 직렬화를 쓴다
     *
     * <p>{@code couponCacheManager} 로 넣으면 {@code GenericJackson2JsonRedisSerializer} 의 기본 타이핑 때문에
     * {@code ["FQCN", {...}]} 래퍼 배열로 저장되는데, 읽는 쪽은 {@code Jackson2JsonRedisSerializer<CouponIssueResponse>}
     * 라 배열 토큰에서 역직렬화가 깨진다. 같은 키를 두 직렬화가 나눠 쓰던 것이 원인이다
     */
    private void writeItemCache(CouponIssueResponse responseDto) {
        couponCacheTemplate.opsForValue()
                .set(CACHE_PREFIX + responseDto.getCouponIssueId(), responseDto, ITEM_CACHE_TTL);
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

    // DB 커밋이 끝난 뒤에 Redis 를 건드린다. 커밋 전에 쓰면 롤백된 결과가 Redis 에만 남아
    // 두 저장소가 어긋난다. 트랜잭션 밖에서 불린 경우엔 즉시 실행한다.
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}