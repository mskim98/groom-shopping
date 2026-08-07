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
 * 쿠폰 비동기 발급 (Strangler).
 *
 * <p>기존 동기 발급(Redisson 락)을 유지한 채 추가되는 비동기 경로다. 요청을 큐(Kafka)로 받아
 * couponId 키로 같은 파티션에 보내면 단일 컨슈머가 직렬 처리하므로 분산 락 없이 동시성이 해소된다.
 * 클라이언트는 즉시 requestId 를 받고, 처리 결과는 Redis 상태값을 polling 해서 확인한다.
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

    /**
     * 발급 요청을 큐에 적재하고 즉시 requestId 를 반환한다. (컨트롤러는 202 Accepted)
     */
    public String enqueue(Long couponId, Long userId) {
        String requestId = UUID.randomUUID().toString();
        // 초기 상태 WAITING 기록 (polling 시 "대기 중" 표시용)
        redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + requestId, STATUS_WAITING, STATUS_TTL);

        CouponIssueRequestEvent event = new CouponIssueRequestEvent(requestId, couponId, userId);
        // key = couponId → 같은 쿠폰 요청은 같은 파티션(직렬 처리)
        send(REQUEST_TOPIC, couponId.toString(), event);
        log.info("[COUPON_ASYNC_ENQUEUE] requestId={}, couponId={}, userId={}", requestId, couponId, userId);
        return requestId;
    }

    /**
     * 컨슈머가 호출하는 실제 발급 처리. 직렬 처리되므로 락 없이 동기 경로와 같은 발급 코어를 재사용한다.
     */
    public void process(CouponIssueRequestEvent event) {
        String statusKey = STATUS_KEY_PREFIX + event.requestId();
        try {
            User user = userRepository.findById(event.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            // 동기 경로와 같은 Lua 원자 연산(재고·중복 검증 + 차감)을 락 없이 호출한다
            // 파티션 단위 직렬 소비라 배타 제어가 이미 성립하고, 재고의 단일 진실은 Redis 로 유지된다
            couponIssueService.issueCouponWithoutLock(event.couponId(), user);

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
