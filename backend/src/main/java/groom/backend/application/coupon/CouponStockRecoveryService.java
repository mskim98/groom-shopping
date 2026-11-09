package groom.backend.application.coupon;

import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.kafka.KafkaLagReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Redis 소실 복구 - 발급 이력과 카프카 랙으로 재고를 되세운다
 *
 * <p>Redis 가 통째로 사라지면 재고 키와 발급자 명단이 함께 없어진다
 * 이때 발급 이력만으로 재고를 복원하면 값이 실제보다 많아진다 - 그 오차를 랙으로 메운다
 *
 * <p>세 겹으로 막는다
 * <ol>
 *   <li>AOF {@code everysec} 로 소실 자체를 최대 1 초치로 줄인다</li>
 *   <li>그래도 사라지면 여기서 랙을 빼서 복원한다</li>
 *   <li>그래도 넘으면 초과 발급 경보가 그 쿠폰의 접수를 막는다</li>
 * </ol>
 * 각 층이 앞 층을 대신하지 않고 뒤를 받친다
 *
 * <p>복구가 도는 동안 접수를 받지 않는다. 재고를 세우는 중에 요청을 받으면
 * 판정 근거가 아직 없는 상태에서 통과 여부를 정하게 된다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponStockRecoveryService {

    private static final int PAGE_SIZE = 500;

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponStockRedisRepository couponStockRedisRepository;
    private final KafkaLagReader kafkaLagReader;

    // 접수 경로가 읽는다. 복구 중에는 게이트를 돌리지 않고 503 으로 돌려보낸다
    private final AtomicBoolean recovering = new AtomicBoolean(false);

    public boolean isRecovering() {
        return recovering.get();
    }

    // 마지막 검사에서 재고 키가 없는 활성 쿠폰이 있었는가. 헬스 체크가 읽기만 한다 - 키를 읽는 일은 복구 스레드가 한다
    private final AtomicBoolean stockMissing = new AtomicBoolean(false);

    public boolean isStockMissing() {
        return stockMissing.get();
    }

    /**
     * 재고 키가 없는 활성 쿠폰이 하나라도 있으면 {@link #recoverAll()} 을 돌린다
     *
     * <p>호출처는 부팅 워밍업과 헬스 체크다. Redis 가 비워진 뒤 재기동 없이도 복구가 돌게 하려는 것이다
     * 키가 모두 있으면 랙도 읽지 않는다 - 헬스 체크마다 Kafka 관리 클라이언트를 만들 이유가 없다
     *
     * @return 복구를 돌렸으면 true
     */
    public boolean recoverIfStockMissing() {
        boolean missing = hasActiveCouponWithoutStock();
        stockMissing.set(missing);
        if (!missing) {
            return false;
        }
        recoverAll();
        // 랙을 읽지 못해 복원이 멈췄다면 키는 여전히 없다 - 다음 검사가 다시 시도할 때까지 그 사실을 남긴다
        stockMissing.set(hasActiveCouponWithoutStock());
        return true;
    }

    private boolean hasActiveCouponWithoutStock() {
        long lastId = 0L;
        List<Coupon> page;
        do {
            page = couponRepository.findActiveByIdGreaterThan(lastId, PageRequest.of(0, PAGE_SIZE));
            for (Coupon coupon : page) {
                if (couponStockRedisRepository.getStock(coupon.getId()) == null) {
                    return true;
                }
                lastId = coupon.getId();
            }
        } while (page.size() == PAGE_SIZE);
        return false;
    }

    /**
     * 재고 키가 사라진 활성 쿠폰을 전부 되세운다
     *
     * <p>재고를 먼저 세우고 명단은 나중에 채운다. 재고가 없으면 판정 자체가 불가능하지만
     * 명단은 없어도 {@code uq_coupon_issue_user} 가 중복을 막아 준다 - 급한 것을 먼저 세운다
     */
    public void recoverAll() {
        if (!recovering.compareAndSet(false, true)) {
            log.warn("[COUPON_RECOVERY_ALREADY_RUNNING]");
            return;
        }
        try {
            long lag = kafkaLagReader.lagOf(CouponAsyncIssueService.REQUEST_TOPIC,
                    CouponAsyncIssueService.CONSUMER_GROUP);
            if (lag < 0) {
                // 랙을 못 읽었다. 0 으로 치고 복원하면 비행 중인 건수만큼 재고가 많아진다
                // 재고가 많아지는 것은 곧 초과 발급이므로, 복원하지 않고 멈춘 채 사람을 부른다
                log.error("[COUPON_RECOVERY_ABORTED] 랙을 읽지 못해 복원값을 계산할 수 없다");
                return;
            }
            long lastId = 0L;
            int recovered = 0;
            List<Coupon> page;
            do {
                page = couponRepository.findActiveByIdGreaterThan(lastId, PageRequest.of(0, PAGE_SIZE));
                for (Coupon coupon : page) {
                    if (recoverOne(coupon, lag)) {
                        recovered++;
                    }
                    lastId = coupon.getId();
                }
            } while (page.size() == PAGE_SIZE);
            log.info("[COUPON_RECOVERY_DONE] 복원 {}건 (lag={})", recovered, lag);
        } finally {
            recovering.set(false);
        }
    }

    /**
     * @return 실제로 심었으면 true
     */
    private boolean recoverOne(Coupon coupon, long lag) {
        Long couponId = coupon.getId();
        if (couponStockRedisRepository.getStock(couponId) != null) {
            // 살아 있는 카운터다. 덮으면 게이트 통과분이 되살아난다
            return false;
        }
        long quantity = coupon.getQuantity() == null ? 0L : coupon.getQuantity();
        long confirmed = couponIssueRepository.countByCoupon_Id(couponId);
        long remaining = quantity - confirmed - lag;

        if (remaining < 0) {
            // 이미 넘었다. 0 으로 막고 알린다 - 되돌리는 것은 사람이 정할 문제다
            log.error("[COUPON_OVER_ISSUED_ON_RECOVERY] couponId={}, quantity={}, confirmed={}, lag={}",
                    couponId, quantity, confirmed, lag);
            remaining = 0;
        }
        // 명단이 먼저다. 재고 키가 먼저 생기면 명단이 비어 있는 동안 이미 받은 사용자가 게이트를 다시 통과한다
        // 키가 없는 동안 접수는 NOT_INITIALIZED 로 503 을 받으므로 이 순서로 열어도 닫힌 채 복원된다
        couponStockRedisRepository.addIssuedUsers(couponId,
                couponIssueRepository.findUserIdsByCouponId(couponId));
        boolean planted = couponStockRedisRepository.initStockIfAbsent(couponId, remaining);
        if (planted) {
            log.warn("[COUPON_STOCK_RECOVERED] couponId={}, remaining={}, confirmed={}, lag={}",
                    couponId, remaining, confirmed, lag);
        }
        return planted;
    }
}
