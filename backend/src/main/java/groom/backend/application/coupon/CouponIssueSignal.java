package groom.backend.application.coupon;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 발급 요청이 확정(SUCCESS · FAILED)됐다는 사실을 기다리는 요청 스레드들에게 알리는 Pub/Sub 신호
 *
 * <p>상태 키는 컨슈머가 먼저 쓰고, 이 신호는 그 뒤에 낸다 - 신호를 받은 대기자가 곧바로 키를 읽어도 서 있다.
 * 신호는 대기를 일찍 깨우는 용도이고 답은 상태 키가 정한다. 결제 {@link groom.backend.application.payment.ConfirmCompletionSignal} 과 같은 구조다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueSignal {

    // 메시지는 "<requestId>:<SUCCESS|FAILED>"
    public static final String CHANNEL = "coupon:issue:done";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";

    private final StringRedisTemplate redis;

    /**
     * 확정된 요청마다 신호를 낸다. 배치 하나가 한 번의 파이프라인이다 - 건마다 왕복하면 배치 크기만큼 왕복이 는다. executePipelined 를 쓰지 않는 것은 컨슈머의 배달 횟수 조회가 파이프라인 한 번이라는 시험(CouponStreamAutoClaimTest)이 그 호출을 세기 때문이다
     *
     * <p>Redis 오류는 삼킨다. 이미 커밋된 발급을 되돌릴 수 없고, 신호를 놓친 대기자는 재확인 간격 뒤 상태 키로 깬다
     *
     * @param statuses requestId → 상태 키에 쓴 값({@code SUCCESS} 또는 {@code FAILED:사유})
     */
    public void publish(Map<String, String> statuses) {
        if (statuses.isEmpty()) {
            return;
        }
        byte[] channel = CHANNEL.getBytes(StandardCharsets.UTF_8);
        try {
            redis.execute((RedisCallback<Object>) connection -> {
                connection.openPipeline();
                try {
                    statuses.forEach((requestId, status) -> {
                        String result = CouponIssueStatus.SUCCESS.equals(status) ? SUCCESS : FAILED;
                        connection.publish(channel, (requestId + ":" + result).getBytes(StandardCharsets.UTF_8));
                    });
                } finally {
                    connection.closePipeline();
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("[COUPON_ISSUE_SIGNAL_FAILED] 신호를 내지 못함, 기다리는 요청은 재확인 때 상태 키로 확인 - Count: {}, Error: {}",
                    statuses.size(), e.getMessage());
        }
    }
}
