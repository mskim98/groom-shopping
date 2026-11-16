package groom.backend.application.coupon;

import groom.backend.application.coupon.CouponIssueBatchWriter.InsertOutcome;
import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.coupon.model.entity.CouponIssue;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 접수한 발급 요청이 확정될 때까지 요청 스레드가 기다려 최종 결과를 돌려준다
 *
 * <p>흐름 : 대기자 등록 → 상태 키 한 번 읽기(등록 전에 나간 신호를 놓치지 않는다) → future 를 재확인 간격 단위로 기다리며
 * 간격마다 상태 키를 다시 읽기(메시지 하나를 잃어도 간격 하나만큼만 늦는다) → SUCCESS · FAILED 면 답한다.
 * 대기 상한이 지났거나 Redis 가 오류를 던지면 요청 스레드가 자기 행을 직접 넣어 마무리한다({@link #fallback}).
 * 대기는 {@code synchronized} 밖에서 한다 - 가상 스레드가 캐리어에 붙들리지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueWaitService {

    private final CouponAsyncIssueService asyncIssueService;
    private final CouponIssueWaiters waiters;
    private final CouponIssueBatchWriter batchWriter;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponStockRedisRepository stockRepository;
    private final MyCouponCacheEvictor myCouponCacheEvictor;

    // 이 시간 안에 확정되지 않으면 요청 스레드가 직접 마무리한다
    @Value("${coupon.issue.wait-timeout-ms:10000}")
    private long waitTimeoutMs = 10_000L;
    // 신호를 기다리는 한 번의 길이. 끝날 때마다 상태 키를 다시 읽는다
    @Value("${coupon.issue.wait-recheck-ms:500}")
    private long recheckMs = 500L;

    /**
     * @return 확정 결과. 대기 중 스레드가 인터럽트되면 접수 결과(ACCEPTED)를 돌려 클라이언트가 상태 조회로 확인하게 한다
     */
    public CouponIssueResult awaitFinal(Long couponId, Long userId, String requestId) {
        CompletableFuture<String> waiter = waiters.register(requestId);
        String verdict;
        try {
            verdict = waitForVerdict(requestId, waiter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CouponIssueResult.accepted(requestId);
        } catch (RuntimeException e) {
            // Redis 읽기 오류다. 컨슈머의 확정 여부를 알 수 없으므로 아래 fallback 이 DB 에서 마무리한다
            log.warn("[COUPON_ISSUE_WAIT_REDIS_FAILED] couponId={}, userId={}, error={}", couponId, userId, e.toString());
            verdict = null;
        } finally {
            waiters.remove(requestId, waiter);
        }
        return verdict == null ? fallback(couponId, userId, requestId) : toResult(couponId, userId, verdict);
    }

    /**
     * @return 상태 키 값(SUCCESS 또는 FAILED:사유). 대기 상한이 지났으면 null
     */
    private String waitForVerdict(String requestId, CompletableFuture<String> waiter) throws InterruptedException {
        long deadline = System.nanoTime() + waitTimeoutMs * 1_000_000L;
        String status = asyncIssueService.getStatus(requestId);
        if (isFinal(status)) {
            waiters.count("key");
            return status;
        }
        while (true) {
            long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMs <= 0) {
                return null;
            }
            try {
                String signaled = waiter.get(Math.min(recheckMs, leftMs), TimeUnit.MILLISECONDS);
                waiters.count("pubsub");
                return "SUCCESS".equals(signaled) ? CouponIssueStatus.SUCCESS : failureStatus(requestId);
            } catch (TimeoutException e) {
                status = asyncIssueService.getStatus(requestId);
                if (isFinal(status)) {
                    waiters.count("key");
                    return status;
                }
            } catch (ExecutionException e) {
                // 등록부는 future 를 정상 완료로만 끝낸다
                throw new IllegalStateException(e);
            }
        }
    }

    private static boolean isFinal(String status) {
        return CouponIssueStatus.SUCCESS.equals(status) || status.startsWith(CouponIssueStatus.FAILED_PREFIX);
    }

    // FAILED 신호를 받은 뒤 사유를 키에서 읽는다. 키가 이미 사라졌으면 사유를 알 수 없으므로 서버 오류로 둔다
    private String failureStatus(String requestId) {
        String status = asyncIssueService.getStatus(requestId);
        return status.startsWith(CouponIssueStatus.FAILED_PREFIX) ? status
                : CouponIssueStatus.FAILED_PREFIX + ErrorCode.SERVER_ERROR.name();
    }

    private CouponIssueResult toResult(Long couponId, Long userId, String status) {
        if (CouponIssueStatus.SUCCESS.equals(status)) {
            return success(couponId, userId);
        }
        String reason = status.substring(CouponIssueStatus.FAILED_PREFIX.length());
        if (ErrorCode.COUPON_OUT_OF_STOCK.name().equals(reason)) {
            return CouponIssueResult.soldOut();
        }
        if (ErrorCode.COUPON_ALREADY_ISSUED.name().equals(reason)) {
            return CouponIssueResult.alreadyIssued();
        }
        if (ErrorCode.COUPON_NOT_FOUND.name().equals(reason)) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }
        // DLQ 로 간 요청이다(예약은 되돌려졌다). 다시 시도하면 되는 상태라 503 으로 돌려보낸다
        return CouponIssueResult.unavailable();
    }

    // 확정된 행의 번호는 행에서 읽는다 - 상태 키와 신호는 발급 번호를 싣지 않는다
    private CouponIssueResult success(Long couponId, Long userId) {
        return CouponIssueResult.success(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)
                .map(CouponIssue::getId).orElse(null));
    }

    /**
     * 대기가 실패한 요청을 요청 스레드가 직접 마무리한다 - 컨슈머와 같은 배치 쓰기 문장(쿠폰 행 잠금 · 남은 자리 확인 ·
     * {@code ON CONFLICT (coupon_id, user_id) DO NOTHING RETURNING})을 한 건으로 쓴다
     *
     * <p>행을 넣었든 이미 있었든(같은 requestId) 발급은 확정이라 SUCCESS 다. 이후 컨슈머가 같은 항목을 만나면
     * request_id 비교로 소유를 알아보고 ack 만 한다. 자리가 없거나 쿠폰이 끝났으면 Redis 예약은 컨슈머가 되돌린다 -
     * 여기서 되돌리면 컨슈머가 같은 항목을 처리할 때 한 번 더 되돌린다
     */
    private CouponIssueResult fallback(Long couponId, Long userId, String requestId) {
        waiters.count("fallback");
        InsertOutcome outcome;
        try {
            outcome = batchWriter.insertAll(couponId, Map.of(userId, requestId));
        } catch (RuntimeException e) {
            log.error("[COUPON_ISSUE_FALLBACK_DB_FAILED] couponId={}, userId={}, error={}", couponId, userId, e.toString());
            return CouponIssueResult.unavailable();
        }
        if (!outcome.couponUsable()) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }
        if (outcome.owned().contains(requestId)) {
            markSuccess(requestId);
            myCouponCacheEvictor.evict(userId);
            return success(couponId, userId);
        }
        return outcome.soldOut().contains(requestId) ? CouponIssueResult.soldOut() : CouponIssueResult.alreadyIssued();
    }

    // 폴링하는 클라이언트와 컨슈머가 같은 답을 보게 상태 키를 SUCCESS 로 둔다. Redis 가 죽었으면 행이 이미 답이다
    private void markSuccess(String requestId) {
        try {
            stockRepository.setStatuses(Map.of(requestId, CouponIssueStatus.SUCCESS));
        } catch (RuntimeException e) {
            log.warn("[COUPON_ISSUE_FALLBACK_STATUS_FAILED] requestId={}, error={}", requestId, e.toString());
        }
    }
}
