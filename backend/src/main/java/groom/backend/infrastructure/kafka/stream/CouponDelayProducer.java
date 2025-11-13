package groom.backend.infrastructure.kafka.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
/**
 * <h3>Kafka Producer: 쿠폰 지연 이벤트 "발행(생산)"</h3>
 *
 * {@link CouponDelayEvent} 객체를 생성하여 Kafka의 {@code coupon-delay-events} 토픽으로
 * 메시지를 전송(Publish)하는 역할을 합니다.<br>
 * 이 클래스는 Spring에서 "Bean"으로 등록되어,
 * API 컨트롤러나 서비스 레이어에서 주입받아 사용됩니다.
 */
@Slf4j // 로그 사용을 위한 Lombok 어노테이션
@Component // 이 클래스를 Spring Bean으로 등록 (다른 곳에서 @Autowired로 주입 가능)
@RequiredArgsConstructor // final로 선언된 필드(kafkaTemplate)를 위한 생성자를 자동 생성
public class CouponDelayProducer {

  /**
   * 메시지를 발행할 Kafka 토픽의 이름
   * (이 토픽은 Kafka Streams가 구독하게 됩니다)
   */
  private static final String TOPIC = "coupon-delay-events";

  /**
   * Spring에서 Kafka로 메시지를 보내기 위해 제공하는 핵심 도구입니다.
   * <String, CouponDelayEvent>는
   * - Key: 메시지 키 (String 타입)
   * - Value: 메시지 본체 (CouponDelayEvent 객체)
   * 임을 의미합니다.
   */
  private final KafkaTemplate<String, CouponDelayEvent> kafkaTemplate;

  /**
   * 쿠폰 지연 이벤트를 Kafka 토픽으로 발행합니다.
   * @param event Kafka로 보낼 쿠폰 이벤트 데이터
   */
  public void publishCouponDelayEvent(CouponDelayEvent event) {
    long start = System.currentTimeMillis();

    // Kafka로 메시지를 비동기 전송합니다.
    // kafkaTemplate.send(토픽이름, 메시지키, 메시지본체)
    //
    // [중요] 메시지 키 (String.valueOf(event.getCouponId())):
    // Kafka는 '메시지 키'를 기준으로 메시지가 저장될 '파티션(Partition)'을 결정합니다.
    // 동일한 키(예: 동일한 couponId)를 가진 메시지는 항상 동일한 파티션으로 전송됩니다.
    // -> 동일 파티션 내에서는 메시지 처리 순서가 보장됩니다.
    //    (예: 1번 쿠폰에 대한 이벤트는 항상 순서대로 처리됨)
    CompletableFuture<SendResult<String, CouponDelayEvent>> f =
            kafkaTemplate.send(TOPIC, String.valueOf(event.getCouponId()), event);

    log.info("[KAFKA_PUBLISH_START] couponId={}, delay={}ms, eventTs={}",
            event.getCouponId(), event.getDelayMillis(), event.getTimestamp());

    // [중요] Kafka 전송은 '비동기(Non-Blocking)'입니다.
    // send() 메소드는 전송을 '요청'하고 즉시 반환되며, 실제 전송은 백그라운드 스레드가 처리합니다.
    // whenComplete는 이 비동기 작업이 "완료되었을 때" (성공하든 실패하든)
    // 결과를 로깅하기 위해 등록하는 '콜백(Callback)'입니다.
    f.whenComplete((res, ex) -> {
      if (ex == null) {
        // 'res' (SendResult)에는 전송 성공 결과가 담겨 있습니다.
        // - partition: 메시지가 저장된 파티션 번호
        // - offset: 해당 파티션 내에서 메시지의 고유한 위치(주소) 번호
        log.info("[KAFKA_PUBLISH_COMPLETE] couponId={}, took={}ms, partition={}, offset={}",
                event.getCouponId(),
                System.currentTimeMillis() - start,
                res.getRecordMetadata().partition(), // 몇 번 파티션(큐)에 저장되었는지
                res.getRecordMetadata().offset());   // 해당 큐의 몇 번째 순서에 저장되었는지
      } else {
        // 'ex' (Exception)에는 전송 실패 원인이 담겨 있습니다.
        // (예: Kafka 브로커 다운, 메시지 크기 초과 등)
        log.error("[KAFKA_PUBLISH_FAILED] couponId={}, took={}ms, error={}",
                event.getCouponId(), System.currentTimeMillis() - start, ex.getMessage(), ex);
      }
    });
  }
}
