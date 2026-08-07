package groom.backend.application.coupon;

import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 재고 대조·보정 배치.
 *
 * <p>Lua 게이트는 통과분을 되돌리지 않으므로, 컨슈머 확정에서 탈락했거나 크래시로 유실된 건이 쌓이면
 * 실제 재고보다 게이트가 먼저 마감된다. 이 배치가 그 좁아짐을 되돌린다
 *
 * <p><b>분산 락이 지키는 대상이 바로 이 배치다.</b> 서버를 N 대로 늘리면 {@code @Scheduled} 가
 * N 번 실행되고, 같은 쿠폰의 재고 키를 동시에 고쳐 쓰면 보정이 서로를 덮는다
 * 락을 쓸 조건은 빈도가 낮고 · 임계 구역이 길고 · 중복 실행의 피해가 큰 경우인데 배치는 셋 다 맞고,
 * 발급 경로는 셋 다 반대여서 그쪽에서는 걷어냈다
 */
// 끌 수 있어야 한다. 이 배치는 Redis 재고를 되돌리므로, 재고와 DB 를 일부러 어긋나게 두고
// 게이트 동작을 보는 통합 테스트가 배치와 겹치면 그 전제를 배치가 지워 버린다
@Slf4j
@Service
@ConditionalOnProperty(name = "coupon.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class CouponStockReconciliationService {

    private static final String RECONCILE_LOCK_KEY = "coupon:reconcile:lock";
    // 대기하지 않는다. 다른 인스턴스가 이미 돌고 있으면 이번 주기는 건너뛰면 된다
    private static final long LOCK_WAIT_SECONDS = 0L;

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponStockRedisRepository couponStockRedisRepository;
    private final RedissonClient redissonClient;
    private final Counter correctedCounter;
    private final Counter loosenedCounter;
    private final Counter orphanCounter;

    @Value("${coupon.reconcile.batch-size:500}")
    private int batchSize;

    public CouponStockReconciliationService(CouponRepository couponRepository,
                                            CouponIssueRepository couponIssueRepository,
                                            CouponStockRedisRepository couponStockRedisRepository,
                                            RedissonClient redissonClient,
                                            MeterRegistry meterRegistry) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.couponStockRedisRepository = couponStockRedisRepository;
        this.redissonClient = redissonClient;
        this.correctedCounter = Counter.builder("coupon.stock.reconcile.corrected")
                .description("게이트가 좁아져 재고를 되돌린 횟수").register(meterRegistry);
        this.loosenedCounter = Counter.builder("coupon.stock.reconcile.loosened")
                .description("게이트가 느슨해 경고만 남긴 횟수").register(meterRegistry);
        this.orphanCounter = Counter.builder("coupon.stock.reconcile.orphans")
                .description("종료 쿠폰에서 회수한 고아 예약 수").register(meterRegistry);
    }

    /**
     * 주기 실행 진입점. 인스턴스 중 하나만 실제로 돈다
     */
    @Scheduled(fixedDelayString = "${coupon.reconcile.delay-ms:60000}")
    public void reconcileScheduled() {
        RLock lock = redissonClient.getLock(RECONCILE_LOCK_KEY);
        boolean acquired = false;
        try {
            // leaseTime 을 생략해 워치독이 TTL 을 갱신한다. 고정 leaseTime 은 스캔이 길어지면
            // 작업 도중 락이 조용히 풀려 두 인스턴스가 같은 키를 고치게 만든다
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[COUPON_RECONCILE_SKIP] 다른 인스턴스가 실행 중");
                return;
            }
            reconcileAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[COUPON_RECONCILE_INTERRUPTED]");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 전 쿠폰을 키셋 페이지네이션으로 훑는다
    // 스캔 전체를 한 트랜잭션으로 묶지 않는다 - 락 보유 구간만큼 DB 커넥션을 점유하게 되고,
    // 이 배치는 페이지마다 읽고 Redis 를 고치는 작업이라 하나의 일관된 스냅숏이 필요하지도 않다
    public void reconcileAll() {
        long lastId = 0L;
        int scanned = 0;
        List<Coupon> page;
        do {
            page = couponRepository.findAllByIdGreaterThanOrderById(lastId, PageRequest.of(0, batchSize));
            for (Coupon coupon : page) {
                ReconcileReport report = reconcileCoupon(coupon);
                if (report != null) {
                    scanned++;
                }
                lastId = coupon.getId();
            }
        } while (page.size() == batchSize);
        log.info("[COUPON_RECONCILE_DONE] 대조 {}건 (batchSize={})", scanned, batchSize);
    }

    /**
     * 쿠폰 한 건을 대조·보정한다. 재고 키가 없으면 {@code null} 을 반환한다
     *
     * <p>대조식 : 기대 재고 = DB 잔여수량 + DB 확정발급수 − 발급자 SET 크기
     * 초기 수량을 저장하지 않으므로 두 저장소의 회계를 빼서 얻는다.
     * 진행 중 예약(게이트 통과·DB 미확정)은 SET 에만 잡히므로 기대값이 그만큼 낮아지고,
     * 그래서 이 식은 <b>진행 중인 재고를 되살리지 않는다</b>
     */
    public ReconcileReport reconcileCoupon(Coupon coupon) {
        Long couponId = coupon.getId();
        Long redisStock = couponStockRedisRepository.getStock(couponId);
        if (redisStock == null) {
            // 워밍업 대상이 아니거나 아직 심기 전이다. 여기서 심으면 워밍업의 SETNX 규칙을 우회하게 된다
            return null;
        }

        long dbQuantity = coupon.getQuantity() == null ? 0L : coupon.getQuantity();
        long confirmed = couponIssueRepository.countByCoupon_Id(couponId);
        long reserved = couponStockRedisRepository.countIssuedUsers(couponId);
        long expected = dbQuantity + confirmed - reserved;

        int orphansRemoved = 0;
        if (isFinished(coupon)) {
            orphansRemoved = removeOrphanReservations(couponId);
            if (orphansRemoved > 0) {
                // 고아를 뺐으므로 기대값을 다시 계산한다
                reserved -= orphansRemoved;
                expected = dbQuantity + confirmed - reserved;
            }
        }

        long correctedTo = redisStock;
        if (redisStock < expected) {
            // 게이트가 실제보다 좁다. 되돌리지 않은 통과분이 쌓인 상태다
            couponStockRedisRepository.initStock(couponId, expected);
            correctedTo = expected;
            correctedCounter.increment();
            log.warn("[COUPON_RECONCILE_WIDEN] couponId={}, {} -> {}", couponId, redisStock, expected);
        } else if (redisStock > expected) {
            // 내리지 않는다. 내리면 이미 게이트를 통과한 요청의 자리를 뺏어 확정 단계에서 실패시킨다
            // 느슨한 게이트가 초과발급으로 이어지지 않는 근거는 DB 조건부 UPDATE 와 uq_coupon_issue_user 다
            loosenedCounter.increment();
            log.warn("[COUPON_RECONCILE_LOOSE] couponId={}, redis={}, expected={} (내리지 않음)",
                    couponId, redisStock, expected);
        }

        return new ReconcileReport(couponId, redisStock, expected, correctedTo, orphansRemoved);
    }

    // 종료된 쿠폰만 대상으로 한다. 진행 중 쿠폰에서는 고아와 in-flight 예약을 구분할 수 없다
    private boolean isFinished(Coupon coupon) {
        return Boolean.FALSE.equals(coupon.getIsActive())
                || (coupon.getExpireDate() != null && coupon.getExpireDate().isBefore(LocalDate.now()));
    }

    private int removeOrphanReservations(Long couponId) {
        Set<String> reservedUsers = couponStockRedisRepository.getIssuedUsers(couponId);
        if (reservedUsers.isEmpty()) {
            return 0;
        }
        Set<Long> confirmedUsers = new HashSet<>(couponIssueRepository.findUserIdsByCouponId(couponId));
        List<Long> orphans = new ArrayList<>();
        for (String member : reservedUsers) {
            try {
                Long userId = Long.parseLong(member);
                if (!confirmedUsers.contains(userId)) {
                    orphans.add(userId);
                }
            } catch (NumberFormatException e) {
                log.warn("[COUPON_RECONCILE_BAD_MEMBER] couponId={}, member={}", couponId, member);
            }
        }
        if (orphans.isEmpty()) {
            return 0;
        }
        couponStockRedisRepository.removeIssuedUsers(couponId, orphans);
        orphanCounter.increment(orphans.size());
        return orphans.size();
    }

    /** 한 쿠폰 대조 결과. 테스트와 로그 분석에 쓴다 */
    public record ReconcileReport(Long couponId, long redisStock, long expectedStock,
                                  long correctedTo, int orphansRemoved) {}
}
