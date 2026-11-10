package groom.backend.application.coupon;

import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.application.coupon.strategy.DbLockIssueStrategy;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.coupon.repository.CouponRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 쿠폰 비동기 발급 접수
 *
 * <p>흐름 : Lua 접수(판정 · 예약 · 스트림 적재 · 상태 WAITING 이 한 번) → 스트림 컨슈머가 그룹으로 읽어
 * 쿠폰 행 잠금 아래 배치 INSERT → 커밋 뒤 ack · 상태 SUCCESS. 지연된 항목은 유휴 인수가 다시 처리하고,
 * 배달 횟수를 넘은 항목은 DLQ 로 간다. 접수는 판정만 하고 즉시 돌려보낸다 -
 * 저장이 응답 경로 밖으로 나가므로 잠금 대기 상한 같은 손잡이가 없다
 *
 * <p>Redis 가 끊기거나 {@code redis-coupon} 회로가 열려 있으면 502 · 503 으로 돌려보내지 않고
 * {@link DbLockIssueStrategy} 경로(쿠폰 행 잠금 · DB 발급 수 대비 수량 확인 · INSERT)로 동기 발급해 그 자리에서 답한다.
 * 느려지는 것이 이 경로의 대가이고, Redis 가 돌아오면 회로가 닫혀 자동으로 접수 경로로 돌아온다
 *
 * <p>클라이언트는 즉시 requestId 를 받고 Redis 상태값을 폴링한다. 대기 순번은 응답하지 않는다 -
 * 판정은 이미 접수 시점에 끝났고 저장 순서는 약속할 수 있는 것이 아니다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponAsyncIssueService {

    private final CouponRepository couponRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final CouponStockRedisRepository couponStockRedisRepository;
    private final CouponStockRecoveryService couponStockRecoveryService;
    private final DbLockIssueStrategy dbLockIssueStrategy;
    private final CouponRedisCircuit redisCircuit;
    // DB 경로로 발급한 사용자의 목록 캐시를 비운다 - 스트림 경로는 컨슈머가 확정 뒤에 비운다
    private final MyCouponCacheEvictor myCouponCacheEvictor;

    /**
     * 접수 - 판정하고 스트림에 싣는다. 여기서 기다리는 일이 없어야 한다
     *
     * <p>게이트를 스트림 앞에 두는 이유 : 마감 · 중복이 확정된 요청까지 통과시키면
     * 스트림과 컨슈머가 어차피 버릴 항목을 나르게 된다
     *
     * <p>재고 키가 없으면 판정할 근거가 없다. 심어질 때까지 여기서 기다리지 않고 즉시 503 으로 돌려보낸다 -
     * 접수 경로의 대기는 그대로 요청 스레드 점유가 된다
     */
    public CouponIssueResult enqueue(Long couponId, Long userId) {
        if (couponStockRecoveryService.isRecovering()) {
            // 재고를 세우는 중이다. 지금 판정하면 아직 없는 근거로 통과 여부를 정하게 된다
            return CouponIssueResult.unavailable();
        }
        String requestId = UUID.randomUUID().toString();
        CouponStockRedisRepository.IssueResult gate;
        try {
            gate = redisCircuit.call(() -> couponStockRedisRepository.enqueue(couponId, userId, requestId));
        } catch (DataAccessException | CallNotPermittedException e) {
            // Redis 한 곳에서 판정하므로 끊기면 접수로 통과시킬 수 없다. DB 경로가 같은 쿠폰 행 잠금으로 대신 판정한다
            // 스크립트가 던진 경우 접수 상태는 알 수 없다(Lua 는 원자적이라 일부만 쓰이지 않는다 - 응답만 유실됐을 수 있다)
            // 그 경우 예약이 남아도 복구가 quantity − DB 발급 수로 다시 세울 때 반영되고, 컨슈머 상한이 초과 발급을 막는다
            log.error("[COUPON_REDIS_UNAVAILABLE_DB_FALLBACK] couponId={}, userId={}, error={}",
                    couponId, userId, e.toString());
            return issueThroughDb(couponId, userId);
        }
        switch (gate) {
            case OUT_OF_STOCK -> {
                return CouponIssueResult.soldOut();
            }
            case ALREADY_ISSUED -> {
                return CouponIssueResult.alreadyIssued();
            }
            case NOT_INITIALIZED -> {
                // 게이트만으로는 "아직 안 심었다" 와 "그런 쿠폰이 없다" 가 구별되지 않는다
                // 없는 쿠폰에 503 을 주면 클라이언트가 영원히 재시도하므로 여기서만 한 번 확인한다
                // 조회 1 회가 붙지만 이 분기는 정상 운영에서 돌지 않는다 - 워밍업이 키를 미리 심는다
                if (couponRepository.findById(couponId)
                        .filter(coupon -> Boolean.TRUE.equals(coupon.getIsActive())).isEmpty()) {
                    throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
                }
                log.warn("[COUPON_GATE_NOT_INITIALIZED] strategy=stream couponId={}, userId={}", couponId, userId);
                return CouponIssueResult.unavailable();
            }
            default -> {
                // 통과. 판정 · 재고 차감 · 명단 · 스트림 · 상태가 한 스크립트에서 끝났다
            }
        }
        return CouponIssueResult.accepted(requestId);
    }

    private CouponIssueResult issueThroughDb(Long couponId, Long userId) {
        CouponIssueResult result = dbLockIssueStrategy.issueFor(couponId, userId);
        if (result.status() == CouponIssueResult.Status.SUCCESS) {
            myCouponCacheEvictor.evict(userId);
        }
        return result;
    }

    /**
     * requestId 의 현재 처리 상태를 조회한다 (클라이언트 폴링)
     */
    public String getStatus(String requestId) {
        String status = redisTemplate.opsForValue().get(CouponStockRedisRepository.statusKey(requestId));
        return status != null ? status : CouponIssueStatus.UNKNOWN;
    }
}
