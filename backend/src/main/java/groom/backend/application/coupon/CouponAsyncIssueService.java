package groom.backend.application.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import groom.backend.application.coupon.event.CouponIssueResultEvent;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.repository.UserRepository;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 쿠폰 비동기 발급.
 *
 * <p>유일한 발급 경로다. 접수 단계의 Lua 게이트가 마감·중복을 1차로 자르고, 통과분만
 * couponId 키로 Kafka 에 실린다. 같은 쿠폰의 이벤트는 한 파티션에 모이므로 판정자는 항상 1명이고,
 * 상호배제를 분산 락으로 사는 대신 파티션 소유권으로 얻는다.
 *
 * <p>게이트를 브로커 앞에 둔 이유 : 마감·중복이 확정된 요청까지 통과시키면 큐와 컨슈머가
 * 어차피 버릴 메시지를 나르게 된다.
 *
 * <p>클라이언트는 즉시 requestId 를 받고, 처리 결과는 Redis 상태값을 polling 해서 확인한다.
 * 대기 순번은 응답하지 않는다 - 판정은 컨슈머 도달 순서로 정해지므로 큐 적재 순번을 돌려주면
 * 지킬 수 없는 순서를 약속하게 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponAsyncIssueService {

    private static final String REQUEST_TOPIC = "coupon-issue-requests";
    private static final String RESULT_TOPIC = "coupon-issue-results";
    private static final String STATUS_KEY_PREFIX = "coupon:issue:status:";
    private static final Duration STATUS_TTL = Duration.ofHours(1);

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED_PREFIX = "FAILED:";

    private final CouponIssueService couponIssueService;       // 기존 발급 로직 재사용
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate; // 요청별 처리 상태 저장(polling 용)
    private final KafkaTemplate<String, String> paymentEventKafkaTemplate; // 7.1 String 템플릿 재사용
    private final ObjectMapper objectMapper;
    private final CouponStockRedisRepository couponStockRedisRepository;

    /**
     * 접수 게이트를 통과한 요청만 큐에 적재하고 requestId 를 반환한다. (컨트롤러는 202 Accepted)
     *
     * <p>게이트를 브로커 앞에 둔 이유 : 마감·중복이 확정된 요청은 브로커와 컨슈머 자원을 쓸 필요가 없다
     * 컨슈머 안에 두면 어차피 버려질 메시지가 전부 파티션을 통과한다
     *
     * <p>게이트 통과 순서를 발급 순서로 쓰지 않는다 - 게이트 통과와 {@code send} 사이에 컨텍스트 스위칭이
     * 나면 오프셋 순서가 뒤바뀐다. 순서의 진실은 오프셋 하나뿐이고, 게이트는 마감·중복만 자른다
     */
    public String enqueue(Long couponId, Long userId) {
        CouponStockRedisRepository.IssueResult gate =
                couponStockRedisRepository.tryIssue(couponId, userId);
        boolean gateReserved;
        switch (gate) {
            case OUT_OF_STOCK -> throw new BusinessException(ErrorCode.COUPON_OUT_OF_STOCK);
            case ALREADY_ISSUED -> throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
            case NOT_INITIALIZED -> {
                // 재고 키가 없으면 게이트가 판정할 근거가 없다. 통과시키고 컨슈머의 DB 폴백에 맡긴다
                log.warn("[COUPON_GATE_NOT_INITIALIZED] couponId={}, userId={}", couponId, userId);
                gateReserved = false;
            }
            default -> gateReserved = true;
        }

        String requestId = UUID.randomUUID().toString();
        // 초기 상태 WAITING 기록 (polling 시 "대기 중" 표시용)
        redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + requestId, STATUS_WAITING, STATUS_TTL);

        CouponIssueRequestEvent event =
                new CouponIssueRequestEvent(requestId, couponId, userId, gateReserved);
        // key = couponId → 같은 쿠폰 요청은 같은 파티션(직렬 처리)
        send(REQUEST_TOPIC, couponId.toString(), event);
        log.info("[COUPON_ASYNC_ENQUEUE] requestId={}, couponId={}, userId={}, gateReserved={}",
                requestId, couponId, userId, gateReserved);
        return requestId;
    }

    /**
     * 컨슈머가 호출하는 실제 발급 처리. 파티션 단위 직렬 소비라 락 없이 확정한다.
     */
    public void process(CouponIssueRequestEvent event) {
        String statusKey = STATUS_KEY_PREFIX + event.requestId();
        try {
            User user = userRepository.findById(event.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            if (event.gateReserved()) {
                // 게이트가 이미 재고를 깎았다. 여기서 또 깎으면 실제보다 빨리 마감된다
                couponIssueService.confirmIssue(event.couponId(), user);
            } else {
                issueWithoutGateReservation(event, user);
            }

            redisTemplate.opsForValue().set(statusKey, STATUS_SUCCESS, STATUS_TTL);
            send(RESULT_TOPIC, event.couponId().toString(),
                    new CouponIssueResultEvent(event.requestId(), event.couponId(), event.userId(), STATUS_SUCCESS, null));
            log.info("[COUPON_ASYNC_SUCCESS] requestId={}, couponId={}", event.requestId(), event.couponId());
        } catch (BusinessException e) {
            String reason = e.getErrorCode().name();
            log.warn("[COUPON_ASYNC_FAILED] requestId={}, couponId={}, reason={}",
                    event.requestId(), event.couponId(), reason);
            recordFailure(event, statusKey, reason);
        } catch (RuntimeException e) {
            // BusinessException 이 아닌 실패도 상태를 남긴다
            // 대표적으로 persistIssuedCoupon 의 트랜잭션 timeout 초과가 TransactionTimedOutException 으로 나온다
            // 남기지 않으면 상태 키가 WAITING 인 채 TTL 까지 방치돼 클라이언트 polling 이 영영 대기한다
            log.error("[COUPON_ASYNC_ERROR] requestId={}, couponId={}, error={}",
                    event.requestId(), event.couponId(), e.toString());
            recordFailure(event, statusKey, ErrorCode.SERVER_ERROR.name());
        }
    }

    /**
     * 접수 때 게이트가 판정하지 못한 요청({@code gateReserved=false})을 처리한다
     *
     * <p>접수와 소비 사이에 재고 키가 심어졌을 수 있으므로 게이트를 한 번 더 돌린다
     * 접수 경로에서 재고가 심어지기를 기다리게 하는 대신 큐를 거치는 시간을 복구 시간으로 쓴다 -
     * 접수는 Lua 왕복 1회와 이벤트 발행만 하는 성질을 지키고, 요청 스레드도 붙잡지 않는다
     *
     * <p>여기서 재시도해도 경합하지 않는다 - 같은 쿠폰의 이벤트는 한 파티션에 모이고
     * 그 파티션의 컨슈머는 1명이라, 이 메서드는 쿠폰당 한 번에 하나만 돈다
     *
     * <p>{@code gateReserved=false} 는 접수 때 재고를 깎지 않았다는 뜻이라 여기서 처음 깎는 것이 맞다
     * {@code true} 인 요청을 이 경로로 태우면 같은 요청이 재고를 두 번 깎아 실제보다 빨리 마감된다
     */
    private void issueWithoutGateReservation(CouponIssueRequestEvent event, User user) {
        CouponStockRedisRepository.IssueResult retry =
                couponStockRedisRepository.tryIssue(event.couponId(), event.userId());
        if (retry != CouponStockRedisRepository.IssueResult.NOT_INITIALIZED) {
            // 재고 키가 접수 이후에 심어졌다는 뜻이다. 이 로그가 안 찍히면 재시도가 이득을 못 낸 것이라
            // 복구 경로(워밍업·쿠폰 생성 시 적재)를 먼저 봐야 한다
            log.info("[COUPON_GATE_RETRY] requestId={}, couponId={}, result={}",
                    event.requestId(), event.couponId(), retry);
        }
        switch (retry) {
            // 재시도가 재고를 깎았다. DB 확정이 실패하면 되돌려야 하므로 롤백을 가진 confirmIssue 로 보낸다
            case SUCCESS -> couponIssueService.confirmIssue(event.couponId(), user);
            // 깎지 않았으므로 되돌릴 것이 없다. 예외로 끊어 실패 사유만 남긴다
            case OUT_OF_STOCK -> throw new BusinessException(ErrorCode.COUPON_OUT_OF_STOCK);
            case ALREADY_ISSUED -> throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
            // 재시도해도 재고 키가 없다. DB 비관적 락 폴백이 판정하고 커밋 후 재고를 심는다
            case NOT_INITIALIZED -> couponIssueService.issueCouponInDbOnly(event.couponId(), user);
        }
    }

    // 실패 상태를 Redis 에 기록하고 결과 토픽으로 알린다
    private void recordFailure(CouponIssueRequestEvent event, String statusKey, String reason) {
        redisTemplate.opsForValue().set(statusKey, STATUS_FAILED_PREFIX + reason, STATUS_TTL);
        send(RESULT_TOPIC, event.couponId().toString(),
                new CouponIssueResultEvent(event.requestId(), event.couponId(), event.userId(), "FAILED", reason));
    }

    /**
     * requestId 의 현재 처리 상태를 조회한다. (클라이언트 polling)
     */
    public String getStatus(String requestId) {
        String status = redisTemplate.opsForValue().get(STATUS_KEY_PREFIX + requestId);
        // 만료/미존재 시 알 수 없음으로 응답
        return status != null ? status : "UNKNOWN";
    }

    // 이벤트를 JSON 으로 직렬화해 토픽으로 발행한다.
    private void send(String topic, String key, Object event) {
        try {
            paymentEventKafkaTemplate.send(topic, key, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("[COUPON_ASYNC_PUBLISH_FAILED] topic={}, key={}, error={}", topic, key, e.getMessage());
            throw new BusinessException(ErrorCode.SERVER_ERROR);
        }
    }
}
