package groom.backend.infrastructure.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 결제 보상 최종 실패(GIVEN_UP) DLQ 컨슈머.
// 운영팀이 즉시 인지하도록 고가시성 경보를 남긴다. (Slack Webhook·대시보드·분석 저장은 확장 지점)
@Slf4j
@Component
public class PaymentCompensationDlqConsumer {

    @KafkaListener(
            topics = "payment-compensation-dlq",
            groupId = "payment-compensation-dlq-group",
            containerFactory = "paymentEventKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        log.error("[COMPENSATION_DLQ_ALERT] 결제 보상 최종 실패(GIVEN_UP) 수신 - paymentKey={}, payload={}",
                record.key(), record.value());
        // TODO 확장: Slack Incoming Webhook 알림 + 관리자 대시보드 카운터 + 분석용 영구 보관(S3/BigQuery)
        //          + 관리자 "수동 환불" 워크플로 연동(audit log)
    }
}
