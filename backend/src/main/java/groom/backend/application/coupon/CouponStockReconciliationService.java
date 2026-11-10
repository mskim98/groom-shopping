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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 재고 대조·보정과 마감 처리. <b>진행 중 쿠폰은 건드리지 않는다</b> - 주기 실행은 종료된 쿠폰에만 작용한다
 *
 * <p>진행 중 쿠폰을 대조하지 않는 이유는 둘이다
 * <ul>
 *   <li><b>이벤트 진행 중에는 고아와 처리 중인 요청을 구별할 수단이 없다.</b> 게이트를 통과했는데
 *       아직 저장되지 않은 건은 두 경우가 완전히 같은 모습이라, 되돌리면 남의 자리를 뺏는다
 *       현재 코드도 고아 정리는 이미 쿠폰 종료 후에만 하고 있었다</li>
 *   <li><b>S2 에서 {@code quantity} 가 불변이 되면 대조식이 뒤집힌다.</b> 지금까지의 식
 *       {@code 기대재고 = quantity + 확정발급 − 예약} 은 quantity 가 "남은 수량"일 때만 성립한다
 *       불변 발행 수량에 그 식을 쓰면 확정이 늘수록 기대값이 커져 주기마다 재고를 되살린다</li>
 * </ul>
 *
 * <p>그래서 대조는 <b>종료된 쿠폰</b>에만 적용하고, 진행 중 실패의 보상은 확정 경로가 그 자리에서 한다
 * (게이트 되돌리기 · 재처리 소진 시 자리 반납)
 *
 * <p><b>분산 락이 지키는 대상이 바로 이 작업이다.</b> 서버를 N 대로 늘리면 마감이 N 번 실행되고,
 * 같은 쿠폰의 재고 키를 동시에 고쳐 쓰면 보정이 서로를 덮는다
 * 락을 쓸 조건은 빈도가 낮고 · 임계 구역이 길고 · 중복 실행의 피해가 큰 경우인데 이 작업은 셋 다 맞고,
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
    private final Counter overIssuedCounter;

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
        // 방어선이 Lua 하나로 줄어든 뒤의 최종 감시자다. 이 값이 0 이 아니면 그 쿠폰의 접수를 막는다
        this.overIssuedCounter = Counter.builder("coupon.stock.over.issued")
                .description("발행 수량을 넘겨 발급된 수").register(meterRegistry);
    }

    /**
     * 주기 실행 진입점. 기본 10분마다({@code coupon.reconcile.fixed-delay}, ISO-8601 기간) 돈다
     * 종료된 쿠폰만 대조하므로 진행 중 요청의 자리를 건드리지 않는다 - 진행 중 쿠폰은 {@link #reconcileCoupon} 이
     * 처음에 걸러 내고, 종료된 쿠폰의 고아 예약 회수는 요청 스트림의 길이를 가장 먼저 읽어 0 일 때만 한다
     */
    @Scheduled(fixedDelayString = "${coupon.reconcile.fixed-delay:PT10M}",
            initialDelayString = "${coupon.reconcile.initial-delay-ms:60000}")
    public void scheduledReconcile() {
        reconcileWithLock();
    }

    /**
     * 전 쿠폰 대조 진입점. 인스턴스 중 하나만 실제로 돈다
     *
     * <p>종료된 쿠폰만 대조한다. 진행 중 이벤트에 이 작업을 걸면 처리 중인 요청의 자리를
     * 되돌려 남에게 넘기므로 {@link #reconcileCoupon} 이 진행 중 쿠폰을 건너뛴다
     */
    public void reconcileWithLock() {
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
        if (!isFinished(coupon)) {
            // 진행 중이면 손대지 않는다. 게이트를 통과했는데 아직 저장되지 않은 건과
            // 되돌려야 할 고아가 겉으로 완전히 같아, 여기서 고치면 남의 자리를 뺏는다
            return null;
        }
        Long redisStock = couponStockRedisRepository.getStock(couponId);
        if (redisStock == null) {
            // 워밍업 대상이 아니거나 아직 심기 전이다. 여기서 심으면 워밍업의 SETNX 규칙을 우회하게 된다
            return null;
        }

        long dbQuantity = coupon.getQuantity() == null ? 0L : coupon.getQuantity();
        long confirmed = couponIssueRepository.countByCoupon_Id(couponId);
        long reserved = couponStockRedisRepository.countIssuedUsers(couponId);
        long expected = dbQuantity + confirmed - reserved;

        int orphansRemoved = removeOrphanReservations(couponId);
        if (orphansRemoved > 0) {
            // 고아를 뺐으므로 기대값을 다시 계산한다
            reserved -= orphansRemoved;
            expected = dbQuantity + confirmed - reserved;
        }

        // 종료된 쿠폰의 재고 키는 고쳐 쓰지 않는다. 더 발급될 일이 없어 고칠 이유가 없고,
        // 고치면 그 회차의 마지막 상태를 읽어 판정하는 쪽이 사후에 만들어진 값을 보게 된다
        // 어긋남은 기록만 남긴다 - 그것이 조사에 필요한 상태다
        long correctedTo = redisStock;
        if (redisStock < expected) {
            correctedCounter.increment();
            log.warn("[COUPON_RECONCILE_NARROW] couponId={}, redis={}, expected={} (고치지 않음)",
                    couponId, redisStock, expected);
        } else if (redisStock > expected) {
            loosenedCounter.increment();
            log.warn("[COUPON_RECONCILE_LOOSE] couponId={}, redis={}, expected={} (고치지 않음)",
                    couponId, redisStock, expected);
        }

        return new ReconcileReport(couponId, redisStock, expected, correctedTo, orphansRemoved);
    }

    /**
     * 쿠폰 마감 - 고아 회수 후 확정 발급 수를 한 번 굳히고, 넘었으면 알린다
     *
     * <p>이벤트가 끝난 뒤 1 회만 부른다. 이후로는 값이 변하지 않으므로 리포트가 매번 셀 이유가 없다 -
     * 지난 이벤트 100 개를 조회하는 화면이 {@code COUNT} 100 번 대신 컬럼 읽기 100 번이 된다
     *
     * <p><b>초과 발급을 자동으로 되돌리지 않는다.</b> 회수하려면 "누구 것을 뺏을지" 를 정해야 하는데
     * 그것은 시스템이 판단할 문제가 아니고, 원인을 모른 채 고치면 조사에 필요한 상태가 사라진다
     * 경보의 역할은 고치기가 아니라 번지기 전에 멈추기다
     *
     * @return 굳힌 확정 발급 수
     */
    public long closeCoupon(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId).orElseThrow(
                () -> new IllegalArgumentException("존재하지 않는 쿠폰: " + couponId));
        reconcileCoupon(coupon);

        long issued = couponIssueRepository.countByCoupon_Id(couponId);
        couponRepository.freezeIssuedCount(couponId, issued);

        // quantity 는 S2 이후 불변 발행 수량이다. 이 비교가 성립하는 근거가 그 불변성이다
        long quantity = coupon.getQuantity() == null ? 0L : coupon.getQuantity();
        if (issued > quantity) {
            log.error("[COUPON_OVER_ISSUED] couponId={}, issued={}, quantity={}, over={}",
                    couponId, issued, quantity, issued - quantity);
            overIssuedCounter.increment(issued - quantity);
        }
        log.info("[COUPON_CLOSED] couponId={}, issued={}, quantity={}", couponId, issued, quantity);
        return issued;
    }

    // 종료된 쿠폰만 대상으로 한다. 진행 중 쿠폰에서는 고아와 in-flight 예약을 구분할 수 없다
    private boolean isFinished(Coupon coupon) {
        return Boolean.FALSE.equals(coupon.getIsActive())
                || (coupon.getExpireDate() != null && coupon.getExpireDate().isBefore(LocalDate.now()));
    }

    private int removeOrphanReservations(Long couponId) {
        // 스트림 길이를 명단과 확정 행보다 먼저 읽는다. 명단과 확정 행을 읽은 뒤에 읽으면, 첫 읽기 때 스트림에 있던 요청이 그 사이 커밋되고
        // 길이가 0 으로 읽혀 "확정 행 목록에 없는 사용자" 가 방금 확정된 사용자인 채 명단에서 지워진다
        // 그러면 게이트가 그를 다시 통과시켜 같은 쿠폰을 한 번 더 받고 DB 유니크가 막으면서 재고가 +1 된다
        // 길이가 0 이 된 뒤에 읽은 확정 행에는 그 시점까지 커밋된 요청이 모두 들어 있다
        // 확정된 항목은 컨슈머가 ack 와 함께 스트림에서 지우므로 길이가 곧 확정 대기분이다
        // 건너뛴 회수는 다음 주기 대조가 스트림이 비었을 때 한다
        long backlog = couponStockRedisRepository.streamLength(couponId);
        if (backlog != 0) {
            log.warn("[COUPON_RECONCILE_ORPHAN_SKIPPED_BACKLOG] couponId={}, backlog={} - 고아 예약 회수를 건너뛴다",
                    couponId, backlog);
            return 0;
        }
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
