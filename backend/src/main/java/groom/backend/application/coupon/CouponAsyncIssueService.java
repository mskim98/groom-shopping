package groom.backend.application.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import groom.backend.application.coupon.event.CouponIssueResultEvent;
import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 쿠폰 비동기 발급 - S2 엔진
 *
 * <p>접수는 판정만 하고 즉시 돌려보내고, 저장은 컨슈머가 배치로 한다
 * 이 분리가 S1 의 손잡이(잠금 대기 상한)를 통째로 없앤다 - 짧게 잡으면 재고가 남았는데 실패하고
 * 길게 잡으면 스레드를 오래 붙드는 딜레마 자체가, 저장이 응답 경로 밖으로 나가면 성립하지 않는다
 *
 * <p>발행 키는 {@code userId} 다. {@code couponId} 로 두면 한 쿠폰의 이벤트가 파티션 하나에만 쌓여
 * 파티션을 늘려도 컨슈머가 늘어난 만큼 일하지 못한다. 키를 바꿀 수 있는 근거는 순서 의존을 없앤 것이고,
 * 그 근거가 곧 재고 행 감산 제거다 - 판정이 Redis 한 곳에서 끝나므로 도착 순서가 결과를 바꾸지 않는다
 *
 * <p>클라이언트는 즉시 requestId 를 받고 Redis 상태값을 폴링한다
 * 대기 순번은 응답하지 않는다 - 판정은 이미 접수 시점에 끝났고 저장 순서는 약속할 수 있는 것이 아니다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponAsyncIssueService {

    public static final String REQUEST_TOPIC = "coupon-issue-requests";
    public static final String CONSUMER_GROUP = "coupon-issue-group";
    private static final String RESULT_TOPIC = "coupon-issue-results";
    private static final String STATUS_KEY_PREFIX = "coupon:issue:status:";
    private static final String INIT_LOCK_PREFIX = "coupon:init:";
    private static final Duration STATUS_TTL = Duration.ofHours(1);
    // 초기화 잠금 대기 상한
    // 넘기면 포기한다 - 컨슈머가 오래 멈추면 max.poll.interval.ms(5분)에 걸려 리밸런싱으로 번지고,
    // 그러면 그 배치가 통째로 재처리된다
    private static final long INIT_LOCK_WAIT_SECONDS = 2;

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED_PREFIX = "FAILED:";

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueBatchWriter couponIssueBatchWriter;
    private final CouponStockRedisRepository couponStockRedisRepository;
    private final RedisTemplate<String, String> redisTemplate;
    // 결제와 갈라 둔 전용 템플릿. 여기 타임아웃은 접수 스레드 점유 상한이라 결제와 목적이 다르다
    private final KafkaTemplate<String, String> couponIssueKafkaTemplate;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;
    private final CouponStockRecoveryService couponStockRecoveryService;

    // 성공 결과까지 결과 토픽에 실을지
    // 기본값이 false 인 이유 - 지금 결과 컨슈머는 로그만 남기고 클라이언트는 Redis 상태를 폴링한다
    // 성공까지 실으면 요청 수만큼 메시지가 한 벌 더 돌아 같은 브로커에서 요청 토픽과 경합한다
    // 파티션 선형성을 재는 라운드에서 그 경합은 측정 대상이 아닌 잡음이다
    // SSE 푸시를 붙이는 시점에 켠다
    @Value("${coupon.issue.publish-success-results:false}")
    private boolean publishSuccessResults;

    /**
     * 접수 - 판정하고 큐에 싣는다. 여기서 기다리는 일이 없어야 한다
     *
     * <p>게이트를 브로커 앞에 두는 이유 : 마감·중복이 확정된 요청까지 통과시키면
     * 큐와 컨슈머가 어차피 버릴 메시지를 나르게 된다
     *
     * <p>재고 키가 없으면 판정할 근거가 없다. 심어질 때까지 여기서 기다리지 않고 즉시 503 으로 돌려보낸다 -
     * 접수 경로의 대기는 그대로 요청 스레드 점유가 되고, 그것이 S0 에서 톰캣이 200/200 으로 찼던 이유다
     */
    public CouponIssueResult enqueue(Long couponId, Long userId) {
        if (couponStockRecoveryService.isRecovering()) {
            // 재고를 세우는 중이다. 지금 판정하면 아직 없는 근거로 통과 여부를 정하게 된다
            return CouponIssueResult.unavailable();
        }
        CouponStockRedisRepository.IssueResult gate =
                couponStockRedisRepository.tryIssue(couponId, userId);
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
                log.warn("[COUPON_GATE_NOT_INITIALIZED] strategy=kafka couponId={}, userId={}", couponId, userId);
                return CouponIssueResult.unavailable();
            }
            default -> {
                // 통과. 여기서부터 Redis 재고는 이미 깎였으므로 확정에 실패하면 반드시 되돌려야 한다
            }
        }

        String requestId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + requestId, STATUS_WAITING, STATUS_TTL);

        // gateReserved=true - 접수가 이미 자리를 배정했다. 컨슈머는 다시 판정하지 않는다
        // 키는 userId 다. 같은 쿠폰의 이벤트가 파티션 전체에 흩어져야 컨슈머가 늘어난 만큼 일한다
        publishRequest(requestId, couponId, userId);
        return CouponIssueResult.accepted(requestId);
    }

    /**
     * 요청 이벤트를 발행한다. 발행에 실패하면 배정한 자리를 되돌린다
     *
     * <p>실패는 두 갈래로 나타나고 <b>되돌림이 양쪽 모두에 걸려 있어야 한다</b>
     * <ul>
     *   <li>비동기 - 브로커가 받지 못한 경우. 콜백으로 오며 상한은 {@code delivery.timeout.ms} 가 정한다</li>
     *   <li>동기 - 토픽 메타데이터를 못 받거나 프로듀서 버퍼가 차서 {@code max.block.ms} 를 넘긴 경우.
     *       {@code send} 자체가 던지므로 <b>콜백이 등록되지 않는다</b></li>
     * </ul>
     *
     * <p>동기 갈래를 콜백에만 맡겨 두면 자리를 배정한 채 아무도 되돌리지 않아 그 수량이 잠긴다.
     * 2026-09-02 초당 5,000 도착 측정에서 실제로 발생 - 새로 만든 토픽의 메타데이터 대기가
     * 1초를 넘겨 132건이 동기로 던졌고, 되돌림 로그는 0건인데 재고 132개가 잠겼다
     * (발급 9,868 / 재고 10,000)
     */
    private void publishRequest(String requestId, Long couponId, Long userId) {
        CouponIssueRequestEvent event = new CouponIssueRequestEvent(requestId, couponId, userId, true);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            recoverFailedPublish(requestId, couponId, userId, e);
            throw new BusinessException(ErrorCode.SERVER_ERROR);
        }
        try {
            couponIssueKafkaTemplate.send(REQUEST_TOPIC, userId.toString(), payload)
                    .whenComplete((sent, error) -> {
                        if (error == null) {
                            return;
                        }
                        recoverFailedPublish(requestId, couponId, userId, error);
                    });
        } catch (Exception e) {
            recoverFailedPublish(requestId, couponId, userId, e);
            throw new BusinessException(ErrorCode.SERVER_ERROR);
        }
    }

    // 배정한 자리를 되돌리고 상태를 실패로 남긴다
    // 상태를 남기지 않으면 클라이언트의 폴링이 WAITING 만 보다가 TTL 로 사라져 실패 사유가 부재해진다
    private void recoverFailedPublish(String requestId, Long couponId, Long userId, Throwable cause) {
        log.error("[COUPON_ENQUEUE_PUBLISH_FAILED] couponId={}, userId={}, error={}",
                couponId, userId, cause.toString());
        couponStockRedisRepository.rollbackIssue(couponId, userId);
        redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + requestId,
                STATUS_FAILED_PREFIX + ErrorCode.SERVER_ERROR.name(), STATUS_TTL);
    }

    /**
     * 단건 처리. 배치 경로로 넘겨 확정 규칙을 한 곳에만 둔다
     */
    public void process(CouponIssueRequestEvent event) {
        processBatch(List.of(event));
    }

    /**
     * 컨슈머가 부르는 확정 처리
     *
     * <p>재고를 검사하지 않는다. 게이트를 통과해 여기 왔다는 것이 곧 "발급해도 된다" 는 답이고,
     * 여기서 다시 세면 세는 것과 넣는 것 사이에 남이 끼어들어 잠금이 되돌아온다
     *
     * <p>실패하면 예외를 던져 오프셋 커밋을 막는다. 배치 전체가 다시 오지만
     * {@code ON CONFLICT DO NOTHING} 이라 이미 들어간 행은 조용히 건너뛴다
     */
    public void processBatch(List<CouponIssueRequestEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        // 키가 userId 라 한 배치에 여러 쿠폰이 섞일 수 있다. 쿠폰 단위로 모아야 한 문장으로 넣는다
        Map<Long, List<CouponIssueRequestEvent>> byCoupon = new LinkedHashMap<>();
        for (CouponIssueRequestEvent event : events) {
            byCoupon.computeIfAbsent(event.couponId(), k -> new ArrayList<>()).add(event);
        }
        Map<String, String> statuses = new LinkedHashMap<>();
        List<CouponIssueResultEvent> results = new ArrayList<>();

        for (Map.Entry<Long, List<CouponIssueRequestEvent>> entry : byCoupon.entrySet()) {
            confirmOneCoupon(entry.getKey(), entry.getValue(), statuses, results);
        }
        writeStatuses(statuses);
        for (CouponIssueResultEvent result : results) {
            send(RESULT_TOPIC, String.valueOf(result.userId()), result);
        }
    }

    private void confirmOneCoupon(Long couponId, List<CouponIssueRequestEvent> events,
                                  Map<String, String> statuses, List<CouponIssueResultEvent> results) {
        Optional<Coupon> found = couponRepository.findById(couponId);
        if (found.isEmpty() || !Boolean.TRUE.equals(found.get().getIsActive())) {
            // 자리를 배정받았는데 쿠폰이 사라졌다. 배정분을 되돌리지 않으면 아무도 못 받는 수량이 된다
            for (CouponIssueRequestEvent event : events) {
                couponStockRedisRepository.rollbackIssue(couponId, event.userId());
                fail(event, ErrorCode.COUPON_NOT_FOUND.name(), statuses, results);
            }
            return;
        }
        LocalDateTime expiresAt = LocalDateTime.of(found.get().getExpireDate(), LocalTime.MIN);

        List<CouponIssueRequestEvent> confirmable = new ArrayList<>();
        for (CouponIssueRequestEvent event : events) {
            if (event.gateReserved()) {
                confirmable.add(event);
            } else if (rejudge(found.get(), event, statuses, results)) {
                confirmable.add(event);
            }
        }
        if (confirmable.isEmpty()) {
            return;
        }

        // 한 사용자에 요청이 둘이면 앞의 것만 넣는다. 뒤의 것은 소유하지 못해 아래에서 진짜 중복으로 떨어진다
        Map<Long, String> requestIdByUser = new LinkedHashMap<>();
        for (CouponIssueRequestEvent event : confirmable) {
            requestIdByUser.putIfAbsent(event.userId(), event.requestId());
        }
        // 소유 = 이번에 넣었거나, 앞 시도가 이미 같은 requestId 로 넣어 둔 행이 있는 요청
        Set<String> owned = couponIssueBatchWriter.insertAll(couponId, expiresAt, requestIdByUser);

        for (CouponIssueRequestEvent event : confirmable) {
            if (owned.contains(event.requestId())) {
                statuses.put(event.requestId(), STATUS_SUCCESS);
                if (publishSuccessResults) {
                    results.add(new CouponIssueResultEvent(event.requestId(), couponId, event.userId(),
                            STATUS_SUCCESS, null));
                }
                continue;
            }
            // 다른 요청이 이미 그 쿠폰의 행을 갖고 있다 = 진짜 중복이다
            // 같은 requestId 의 행이면 owned 에 들어와 여기 오지 않는다 - 재전달에서 재고를 되돌리면 +1 씩 부푼다
            // 발급자 SET 에서는 빼지 않는다 - 빼면 게이트가 그를 다시 통과시켜 같은 실패를 반복한다
            // 자리만 되돌려 뒤 요청이 쓰게 한다
            couponStockRedisRepository.rollbackStockOnly(couponId);
            log.warn("[COUPON_DUPLICATE_BLOCKED_BY_DB] couponId={}, userId={}", couponId, event.userId());
            fail(event, ErrorCode.COUPON_ALREADY_ISSUED.name(), statuses, results);
        }
    }

    /**
     * 접수가 판정하지 못한 요청({@code gateReserved=false})을 컨슈머에서 다시 판정한다
     *
     * <p>접수 경로는 이제 이런 이벤트를 만들지 않는다(재고 키가 없으면 503 으로 돌려보낸다)
     * 여기 오는 것은 배포 전에 실린 구 메시지나 Redis 소실 복구 중에 발행된 건이다
     *
     * <p>키가 {@code userId} 라 한 쿠폰의 이벤트가 파티션 4 개에 흩어진다
     * 그래서 재고 키가 없다는 사실을 컨슈머 4 개가 동시에 발견할 수 있고, 초기화 잠금이 필요해진다
     *
     * @return 확정 대상이면 true
     */
    private boolean rejudge(Coupon coupon, CouponIssueRequestEvent event,
                            Map<String, String> statuses, List<CouponIssueResultEvent> results) {
        CouponStockRedisRepository.IssueResult retry =
                couponStockRedisRepository.tryIssue(event.couponId(), event.userId());
        if (retry == CouponStockRedisRepository.IssueResult.NOT_INITIALIZED) {
            try {
                ensureStockInitialized(coupon);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(event, ErrorCode.COUPON_ISSUE_CONTENTION.name(), statuses, results);
                return false;
            }
            retry = couponStockRedisRepository.tryIssue(event.couponId(), event.userId());
        }
        switch (retry) {
            case SUCCESS -> {
                return true;
            }
            case OUT_OF_STOCK -> fail(event, ErrorCode.COUPON_OUT_OF_STOCK.name(), statuses, results);
            case ALREADY_ISSUED -> fail(event, ErrorCode.COUPON_ALREADY_ISSUED.name(), statuses, results);
            // 잠금까지 거치고도 키가 없다. 판정할 근거가 끝내 없으므로 통과시키지 않는다 -
            // 통과시키면 그 뒤에 판정할 사람이 아무도 없어 무제한 발급이 된다
            default -> fail(event, ErrorCode.COUPON_ISSUE_UNAVAILABLE.name(), statuses, results);
        }
        return false;
    }

    /**
     * 재고 키를 심는다. 잠금이 지키는 것은 정확성이 아니라 비용이다
     *
     * <p>잠금 키({@code coupon:init:{couponId}})는 재고 키와 아무 연결이 없다
     * Redis 는 "이 잠금을 가진 자만 저 키를 만질 수 있다" 를 강제하지 않는다
     * 정확성은 {@code SET NX} 가 지키고, 잠금은 {@code COUNT} 쿼리를 컨슈머 수만큼에서 1 회로 줄인다
     *
     * <p>심는 값이 맞아떨어지는 이유 : {@code COUNT} 는 확정된 발급만 세므로 지금 처리 중인 폴백 이벤트는 빠진다
     * 그만큼 재고가 많게 심어지는데, 바로 그 이벤트들이 재판정으로 그 몫을 소비한다
     */
    private void ensureStockInitialized(Coupon coupon) throws InterruptedException {
        Long couponId = coupon.getId();
        RLock initLock = redissonClient.getLock(INIT_LOCK_PREFIX + couponId);
        if (!initLock.tryLock(INIT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
            return;
        }
        try {
            // 기다리는 동안 남이 이미 심었을 수 있다. 얻고 나서 다시 본다
            if (couponStockRedisRepository.getStock(couponId) != null) {
                return;
            }
            long quantity = coupon.getQuantity() == null ? 0L : coupon.getQuantity();
            long remaining = Math.max(0L, quantity - couponIssueRepository.countByCoupon_Id(couponId));
            couponStockRedisRepository.initStockIfAbsent(couponId, remaining);
            log.warn("[COUPON_STOCK_REINITIALIZED] couponId={}, remaining={}", couponId, remaining);
        } finally {
            if (initLock.isHeldByCurrentThread()) {
                initLock.unlock();
            }
        }
    }

    /**
     * 재처리를 다 쓰고도 실패한 배치를 정리한다
     *
     * <p>배정된 자리를 되돌리지 않으면 그 수량은 아무도 받지 못한 채 사라진다
     * 주기 배치를 없앴으므로 이 자리가 마지막 보상 지점이다
     */
    public void abandonBatch(List<CouponIssueRequestEvent> events, Throwable cause) {
        Map<String, String> statuses = new LinkedHashMap<>();
        List<CouponIssueResultEvent> results = new ArrayList<>();
        // 행이 이미 커밋된 요청은 반납하지 않는다 - 되돌리면 DB 에는 행이 있는데 재고만 늘어난다
        // 조회가 실패하면 예외를 그대로 올린다. 행의 유무를 모르는 채 반납하면 초과 발급이고,
        // 반납하지 않는 쪽의 손해는 정산이 메우는 미달 발급이다
        Set<String> committed = new HashSet<>(couponIssueRepository.findExistingRequestIds(
                events.stream().map(CouponIssueRequestEvent::requestId).filter(Objects::nonNull).toList()));
        for (CouponIssueRequestEvent event : events) {
            if (committed.contains(event.requestId())) {
                statuses.put(event.requestId(), STATUS_SUCCESS);
                continue;
            }
            couponStockRedisRepository.rollbackIssue(event.couponId(), event.userId());
            fail(event, ErrorCode.SERVER_ERROR.name(), statuses, results);
        }
        log.error("[COUPON_BATCH_ABANDONED] size={}, committed={}, error={}",
                events.size(), committed.size(), String.valueOf(cause));
        writeStatuses(statuses);
        for (CouponIssueResultEvent result : results) {
            send(RESULT_TOPIC, String.valueOf(result.userId()), result);
        }
    }

    private void fail(CouponIssueRequestEvent event, String reason,
                      Map<String, String> statuses, List<CouponIssueResultEvent> results) {
        statuses.put(event.requestId(), STATUS_FAILED_PREFIX + reason);
        results.add(new CouponIssueResultEvent(event.requestId(), event.couponId(), event.userId(),
                "FAILED", reason));
    }

    /**
     * 상태 키를 한 번의 왕복으로 쓴다
     *
     * <p>건마다 왕복하면 배치로 줄인 왕복이 여기서 그대로 되살아난다
     */
    private void writeStatuses(Map<String, String> statuses) {
        if (statuses.isEmpty()) {
            return;
        }
        // SessionCallback 을 쓰는 이유 - 템플릿이 쓰던 직렬화를 그대로 탄다
        // 저수준 콜백으로 바이트를 직접 넣으면 폴링이 읽는 쪽과 직렬화가 갈릴 수 있고,
        // 그러면 상태가 늘 UNKNOWN 으로 보인다
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                statuses.forEach((requestId, status) ->
                        ops.opsForValue().set(STATUS_KEY_PREFIX + requestId, status, STATUS_TTL));
                return null;
            }
        });
    }

    /**
     * requestId 의 현재 처리 상태를 조회한다 (클라이언트 폴링)
     */
    public String getStatus(String requestId) {
        String status = redisTemplate.opsForValue().get(STATUS_KEY_PREFIX + requestId);
        return status != null ? status : "UNKNOWN";
    }

    private void send(String topic, String key, Object event) {
        try {
            couponIssueKafkaTemplate.send(topic, key, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("[COUPON_ASYNC_PUBLISH_FAILED] topic={}, key={}, error={}", topic, key, e.getMessage());
            throw new BusinessException(ErrorCode.SERVER_ERROR);
        }
    }
}
