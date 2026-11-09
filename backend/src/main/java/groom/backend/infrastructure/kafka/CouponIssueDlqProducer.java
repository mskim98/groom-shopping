package groom.backend.infrastructure.kafka;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 읽을 수 없는 쿠폰 발급 요청을 원문 그대로 DLQ 토픽에 싣는다
 *
 * <p>동기로 보낸다. 건너뛰는 대신 DLQ 에 남기는 것이 목적이라 전송 결과를 모르는 채 오프셋을 넘기면
 * 원문이 어디에도 남지 않는다. 실패하면 예외를 던져 컨슈머가 배치를 다시 받게 한다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueDlqProducer {

    public static final String DLQ_TOPIC = "coupon-issue-dlq";
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> couponIssueKafkaTemplate;

    public void send(String key, String rawPayload, Throwable cause) {
        try {
            couponIssueKafkaTemplate.send(DLQ_TOPIC, key, rawPayload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.error("[COUPON_ISSUE_DLQ_SENT] key={}, payload={}, cause={}", key, rawPayload, String.valueOf(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DLQ 전송 중 인터럽트", e);
        } catch (Exception e) {
            throw new IllegalStateException("DLQ 전송 실패: key=" + key, e);
        }
    }
}
