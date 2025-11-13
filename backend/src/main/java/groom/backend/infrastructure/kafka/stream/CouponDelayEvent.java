package groom.backend.infrastructure.kafka.stream;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <h3>Kafka Event: 쿠폰 지연 처리용 이벤트 (데이터 객체 - DTO)</h3>
 *
 * Kafka 토픽을 통해 전송될 메시지의 실제 데이터(페이로드)를 정의하는 클래스입니다.<br>
 * Producer(생산자)는 이 객체를 만들어 Kafka로 전송하고,
 * Consumer(소비자)는 Kafka로부터 이 객체 형태로 데이터를 전달받습니다.
 *
 * <hr>
 * <h4>전체 이벤트 처리 흐름 (주석 원본)</h4>
 * <ol>
 * <li>Client → LB → API (Producer가 이 Event 객체 생성)</li>
 * <li>Producer → {@code coupon-delay-events} 토픽으로 메시지 발행</li>
 * <li>Kafka Streams가 {@code coupon-delay-events} 토픽 구독</li>
 * <li>Streams가 delayMillis (지연 시간)만큼 대기 후...</li>
 * <li>Streams → {@code coupon-delay-processed} 토픽으로 동일한 메시지 발행</li>
 * <li>Consumer (CouponDelayConsumer)가 {@code coupon-delay-processed} 토픽을 구독하여
 * 즉시 쿠폰 비활성화 로직 수행</li>
 * </ol>
 */
@Data
@NoArgsConstructor // Kafka가 메시지를 Java 객체로 변환(Deserialization)할 때 필요
@AllArgsConstructor
public class CouponDelayEvent {
  /**
   * 비활성화할 쿠폰의 고유 ID
   */
  private Long couponId;

  /**
   * "얼마나" 지연시킬지 결정하는 시간 (밀리초 단위)
   * 예: 3000 (3초 뒤), 3600000 (1시간 뒤)
   * Kafka Streams는 이 값을 참조하여 메시지 처리를 지연시킵니다.
   */
  private Long delayMillis;

  /**
   * 이벤트가 처음 생성된 시간 (밀리초 단위 timestamp)
   * Kafka Streams가 지연 시간을 정확히 계산하기 위한 기준점으로 사용됩니다.
   * (예: 실제 처리 시간 = timestamp + delayMillis)
   */
  private Long timestamp;
}
