package groom.backend.infrastructure.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 쿠폰 비동기 발급 결과 컨슈머. 현재는 로깅까지만(클라이언트는 Redis 상태를 polling).
// SSE 실시간 푸시는 확장 지점(사용자별 SseEmitter 로 결과 전달).
@Slf4j
@Component
public class CouponIssueResultConsumer {

    @KafkaListener(
            topics = "coupon-issue-results",
            groupId = "coupon-issue-result-group",
            containerFactory = "paymentEventKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        log.info("[COUPON_ASYNC_RESULT] payload={}", record.value());
        // TODO 확장: SseService 로 사용자에게 실시간 결과 푸시
    }
}
