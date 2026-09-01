package groom.backend.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 발급 확정 컨슈머 - 검사하지 않고 저장만 한다
 *
 * <p>배치로 받는 이유는 저장 비용의 구성 때문이다. 커넥션 획득 · 트랜잭션 · 네트워크 왕복은
 * 배치 1 회로 줄지만 행 INSERT · 인덱스 갱신 · WAL 기록은 건수 그대로 남는다
 * 줄어드는 쪽만 배치가 가져간다
 *
 * <p>{@code concurrency} 는 파티션 수와 같다. 파티션보다 많이 두면 남는 컨슈머는 배정을 못 받아 놀고,
 * 적게 두면 한 컨슈머가 여러 파티션을 맡아 그만큼 직렬화된다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueRequestConsumer {

    private final CouponAsyncIssueService couponAsyncIssueService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = CouponAsyncIssueService.REQUEST_TOPIC,
            groupId = CouponAsyncIssueService.CONSUMER_GROUP,
            containerFactory = "couponIssueBatchListenerContainerFactory")
    public void consume(List<ConsumerRecord<String, String>> records) {
        List<CouponIssueRequestEvent> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                events.add(objectMapper.readValue(record.value(), CouponIssueRequestEvent.class));
            } catch (Exception e) {
                // 읽을 수 없는 메시지 하나 때문에 배치 전체를 재처리하면 그 배치가 영원히 진행하지 못한다
                // 되돌릴 대상(userId)조차 못 읽은 상태라 여기서 할 수 있는 것은 기록뿐이다
                log.error("[COUPON_ASYNC_DESERIALIZE_FAILED] offset={}, payload={}, error={}",
                        record.offset(), record.value(), e.getMessage());
            }
        }
        // 예외를 삼키지 않는다. 던져야 오프셋이 커밋되지 않고 배치가 다시 온다
        // 재처리에서 중복이 나지 않는 근거는 ON CONFLICT DO NOTHING 이다
        couponAsyncIssueService.processBatch(events);
    }
}
