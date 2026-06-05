package groom.backend.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 쿠폰 비동기 발급 요청 컨슈머. couponId 키 → 같은 파티션 → 직렬 처리(락 불필요).
// 단일 컨슈머(concurrency 1, 파티션 단위)가 순서대로 처리하도록 동작한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueRequestConsumer {

    private final CouponAsyncIssueService couponAsyncIssueService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "coupon-issue-requests",
            groupId = "coupon-issue-group",
            containerFactory = "paymentEventKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            CouponIssueRequestEvent event = objectMapper.readValue(record.value(), CouponIssueRequestEvent.class);
            couponAsyncIssueService.process(event);
        } catch (Exception e) {
            // 역직렬화 등 처리 불가 메시지는 로깅 (재처리 정책은 추후 DLQ 로 확장 가능)
            log.error("[COUPON_ASYNC_CONSUME_ERROR] payload={}, error={}", record.value(), e.getMessage());
        }
    }
}
