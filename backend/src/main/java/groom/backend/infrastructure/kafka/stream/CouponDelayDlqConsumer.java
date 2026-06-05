package groom.backend.infrastructure.kafka.stream;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 쿠폰 지연 처리 DLQ 컨슈머.
// 메인 컨슈머가 지수 백오프 재시도(3회)까지 실패해 이관한 죽은 메시지를 별도로 처리한다.
// 무한 루프 방지를 위해 여기서는 예외를 다시 던지지 않고 경보 로깅만 한다(수동 처리/분석은 확장 지점).
@Slf4j
@Component
public class CouponDelayDlqConsumer {

    @KafkaListener(
            topics = "coupon-delay-dlq",
            groupId = "coupon-delay-dlq-group",
            containerFactory = "paymentEventKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        log.error("[COUPON_DELAY_DLQ_ALERT] 쿠폰 지연 처리 최종 실패 - key={}, value={}",
                record.key(), record.value());
        // TODO 확장: 실패 패턴 분석 저장 + 운영자 알림 + 수동 재처리 트리거
    }
}
