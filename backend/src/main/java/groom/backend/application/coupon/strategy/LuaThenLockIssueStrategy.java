package groom.backend.application.coupon.strategy;

import groom.backend.application.coupon.CouponIssueService;
import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * S1 - 판정을 잠금보다 먼저 하고, 통과한 요청만 잠금 안으로 들여보낸다
 *
 * <p>S0 과의 유일한 차이는 <b>순서</b>다. 판정을 잠금 밖으로 뺐다
 * 그래서 거절될 요청은 데이터베이스에 도달하지 않고, 커넥션을 한 번도 잡지 않는다
 *
 * <p>이 순서가 두 가지를 만든다
 * <ul>
 *   <li><b>Lua 가 필수가 된다</b> - 판정 구간을 잠금이 보호하지 않으므로,
 *       중복 검사부터 차감까지를 한 덩어리로 묶을 다른 수단이 없다.
 *       잠금 안에 판정을 두면 Lua 는 정확성 목적으로는 필요 없다</li>
 *   <li><b>잠금의 역할이 바뀐다</b> - 상호배제가 아니라 유량 제어다.
 *       동시에 데이터베이스를 건드리는 수를 제한할 뿐이고, 수량 정확성은 Lua 가 책임진다</li>
 * </ul>
 *
 * <p>남는 한계 - 임계 구역 안에 여전히 데이터베이스 쓰기가 있다.
 * 그래서 커넥션 풀은 지켜지지만 발급 처리량의 상한은 그대로다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LuaThenLockIssueStrategy implements CouponIssueStrategy {

    private static final String LOCK_KEY_PREFIX = "coupon:issue:lock:";
    // 잠금 대기 상한. 길게 잡으면 병목이 톰캣 스레드로 옮겨가고,
    // 짧게 잡으면 게이트를 통과한 요청이 재고가 남았는데도 처리되지 못하고 실패한다
    // 이 값 자체가 S1 의 딜레마를 만드는 손잡이다
    private static final long LOCK_WAIT_SECONDS = 3;

    private final CouponStockRedisRepository couponStockRedisRepository;
    private final CouponIssueService couponIssueService;
    private final RedissonClient redissonClient;

    @Override
    public String name() {
        return "lua-then-lock";
    }

    @Override
    public CouponIssueResult issue(Long couponId, User user) {
        // 1단계 - 판정. 여기서 거절되면 데이터베이스를 건드리지 않는다
        CouponStockRedisRepository.IssueResult gate =
                couponStockRedisRepository.tryIssue(couponId, user.getId());
        switch (gate) {
            case OUT_OF_STOCK -> {
                return CouponIssueResult.soldOut();
            }
            case ALREADY_ISSUED -> {
                return CouponIssueResult.alreadyIssued();
            }
            case NOT_INITIALIZED -> {
                // 재고 키가 없으면 판정할 근거가 없다. 통과시키지 않는다 -
                // 통과시키면 그 뒤에 판정할 사람이 아무도 없어 무제한 발급이 된다
                // 재시도 가능한 사유로 돌려주고, 워밍업이 심을 때까지 기다리게 한다
                log.warn("[COUPON_GATE_NOT_INITIALIZED] strategy=lua-then-lock couponId={}, userId={}",
                        couponId, user.getId());
                return CouponIssueResult.contention();
            }
            default -> {
                // 통과. 여기서부터 Redis 재고는 이미 깎여 있으므로 실패하면 반드시 되돌려야 한다
            }
        }

        // 2단계 - 통과분만 잠금 안에서 확정
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + couponId);
        boolean acquired = false;
        try {
            // leaseTime 을 주지 않는다. 워치독이 보유 중에 TTL 을 갱신하므로
            // 저장이 예상보다 오래 걸려도 잠금이 먼저 풀려 두 요청이 함께 들어가는 일이 없다
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                // 재고는 남아 있는데 잠금을 제한 시간 안에 얻지 못했다
                // 품절과 섞으면 늘어난 실패가 경합 때문인지 소진 때문인지 구별할 수 없다
                couponStockRedisRepository.rollbackIssue(couponId, user.getId());
                return CouponIssueResult.contention();
            }
            CouponIssueResponse saved = couponIssueService.confirmIssue(couponId, user);
            return CouponIssueResult.success(saved.getCouponIssueId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            couponStockRedisRepository.rollbackIssue(couponId, user.getId());
            return CouponIssueResult.contention();

        } catch (BusinessException be) {
            // confirmIssue 가 이미 Redis 를 되돌렸다. 여기서 또 되돌리면 재고가 두 번 살아난다
            if (be.getErrorCode() == ErrorCode.COUPON_ALREADY_ISSUED) {
                return CouponIssueResult.alreadyIssued();
            }
            if (be.getErrorCode() == ErrorCode.COUPON_OUT_OF_STOCK) {
                return CouponIssueResult.soldOut();
            }
            throw be;

        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
