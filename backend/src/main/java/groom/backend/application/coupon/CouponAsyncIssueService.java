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
    private final CouponQueueRedisRepository couponQueueRedisRepository; // 대기 순번(ZSet)
    private final ObjectMapper objectMapper;

    // 대기 순번 응답용 (position = 내 앞 대기 인원, waiting = 전체 대기 인원)
    public record QueuePosition(long position, long waiting) {}

    /**
     * 발급 요청을 큐에 적재하고 즉시 requestId 를 반환한다. (컨트롤러는 202 Accepted)
     */
    public String enqueue(Long couponId, Long userId) {
        String requestId = UUID.randomUUID().toString();
        // 초기 상태 WAITING 기록 (polling 시 "대기 중" 표시용)
        redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + requestId, STATUS_WAITING, STATUS_TTL);

        // 대기열(ZSet)에 등록 → 본인의 추정 대기 순번을 보여줄 수 있다.
        couponQueueRedisRepository.enqueue(couponId, userId);

        CouponIssueRequestEvent event = new CouponIssueRequestEvent(requestId, couponId, userId);
        // key = couponId → 같은 쿠폰 요청은 같은 파티션(직렬 처리)
        send(REQUEST_TOPIC, couponId.toString(), event);
        log.info("[COUPON_ASYNC_ENQUEUE] requestId={}, couponId={}, userId={}", requestId, couponId, userId);
        return requestId;
    }

    /**
     * 컨슈머가 호출하는 실제 발급 처리. 직렬 처리되므로 락 없이 기존 DB 발급 로직을 재사용한다.
     */
    public void process(CouponIssueRequestEvent event) {
        String statusKey = STATUS_KEY_PREFIX + event.requestId();
        try {
            User user = userRepository.findById(event.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            // 직렬 처리 가정하에 기존 DB 발급 로직(재고·중복 검증 포함)을 그대로 재사용
            couponIssueService.issueCouponInDbOnly(event.couponId(), user);

            redisTemplate.opsForValue().set(statusKey, STATUS_SUCCESS, STATUS_TTL);
            send(RESULT_TOPIC, event.couponId().toString(),
                    new CouponIssueResultEvent(event.requestId(), event.couponId(), event.userId(), STATUS_SUCCESS, null));
            log.info("[COUPON_ASYNC_SUCCESS] requestId={}, couponId={}", event.requestId(), event.couponId());
        } catch (BusinessException e) {
            String reason = e.getErrorCode().name();
            redisTemplate.opsForValue().set(statusKey, STATUS_FAILED_PREFIX + reason, STATUS_TTL);
            send(RESULT_TOPIC, event.couponId().toString(),
                    new CouponIssueResultEvent(event.requestId(), event.couponId(), event.userId(), "FAILED", reason));
            log.warn("[COUPON_ASYNC_FAILED] requestId={}, couponId={}, reason={}",
                    event.requestId(), event.couponId(), reason);
        } finally {
            // 성공/실패 무관하게 대기열에서 제거 → 뒤 사용자의 앞 대기 인원이 줄어든다.
            couponQueueRedisRepository.remove(event.couponId(), event.userId());
        }
    }

    /**
     * 사용자의 현재 대기 순번을 조회한다. position = 내 앞 대기 인원(추정), waiting = 전체 대기 인원.
     * 큐에 없으면(이미 처리됨/미등록) position 0 으로 응답한다.
     */
    public QueuePosition getPosition(Long couponId, Long userId) {
        Long rank = couponQueueRedisRepository.rank(couponId, userId);
        long waiting = couponQueueRedisRepository.size(couponId);
        return new QueuePosition(rank != null ? rank : 0L, waiting);
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
