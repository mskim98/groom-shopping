package groom.backend.infrastructure.kafka.stream;

import groom.backend.domain.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * <h3>Kafka Consumer: 지연 처리 완료된 쿠폰 "소비(처리)"</h3>
 *
 * Kafka Streams가 지연 처리를 완료한 후 {@code coupon-delay-events} 토픽으로
 * 발행한 메시지를 최종적으로 구독(Consume)하는 클래스입니다.<br>
 *
 * 이 Consumer는 메시지를 받는 즉시 쿠폰을 비활성화하는 실제 비즈니스 로직
 * ({@link CouponService#disableCoupon})을 호출합니다.
 */
@Slf4j
@Component // 이 클래스도 Spring Bean으로 등록
@RequiredArgsConstructor // final 필드(couponService) 주입을 위한 생성자 자동 생성
public class CouponDelayConsumer {

  /**
   * 실제 쿠폰 비활성화 로직을 담고 있는 도메인 서비스
   */
  private final CouponService couponService;

  /**
   * Kafka 토픽의 메시지를 구독하는 리스너 메소드입니다.
   *
   * [중요] @KafkaListener:
   * Spring Kafka가 이 메소드를 지정된 토픽의 '메시지 구독자'로 자동 등록합니다.
   * Kafka에 해당 토픽으로 새 메시지가 들어오면 이 메소드가 자동으로 실행됩니다.
   * - groupId: "컨슈머 그룹"의 ID를 지정합니다.
   * - 같은 'groupId'를 가진 컨슈머 인스턴스(애플리케이션)들은 "하나의 팀"으로 동작합니다.
   * - 만약 이 애플리케이션을 3대로 스케일 아웃(확장)하면, 3개의 인스턴스가
   * 'coupon-delay-group' 팀에 속하게 됩니다.
   * - Kafka는 토픽의 메시지를 이 팀원들에게 "중복 없이, 분산하여" 전달합니다.
   * (→ 확장성(Scale-out) 확보)
   * - 또한, 이 'groupId'를 기준으로 "어디까지 메시지를 읽었는지(Offset)"를 Kafka에
   * 기록합니다.
   *
   * @param event Kafka 토픽에서 전달받은 메시지.
   * Spring이 자동으로 JSON/바이트 형태의 메시지를
   * {@link CouponDelayEvent} Java 객체로 변환(Deserialize)하여 주입해 줍니다.
   */
  @KafkaListener(
          topics = "coupon-activate-events",
          groupId = "coupon-delay-group",
          containerFactory = "couponDelayEventConcurrentKafkaListenerContainerFactory")
  public void consume(CouponDelayEvent event) {
    log.info("[Kafka] coupon inactivate reservation event consumed: {}", event);
    try {
      // 전달받은 이벤트의 couponId를 사용하여 실제 비즈니스 로직(쿠폰 비활성화) 실행
      couponService.disableCoupon(event.getCouponId());
      log.info("[KAFKA_CONSUME_SUCCESS] couponId={}", event.getCouponId());

    } catch (Exception e) {
      // [중요] 컨슈머에서 예외(Exception) 처리
      log.error("[KAFKA_CONSUME_FAILED] couponId={}, error={}",
              event.getCouponId(), e.getMessage(), e);

      // 예외를 다시 throw:
      // 만약 예외를 catch하고 아무것도 하지 않으면, Spring Kafka는
      // 이 메시지가 "성공적으로 처리"되었다고 간주하고 다음 메시지로 넘어갈 수 있습니다.
      // (→ 메시지 유실 발생)
      // 예외를 다시 throw하면, Kafka Listener는 이 메시지 처리에 "실패"했음을
      // 인지하고, 설정된 정책(예: 재시도, Dead Letter Queue로 전송 등)에 따라
      // 후속 조치를 시도할 수 있습니다.
      throw e;
    }
  }
}