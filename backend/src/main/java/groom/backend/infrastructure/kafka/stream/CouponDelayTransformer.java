package groom.backend.infrastructure.kafka.stream;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * <h3>Kafka Streams 'Transformer' (저수준 프로세서)</h3>
 *
 * Kafka Streams의 핵심 로직을 담당하는 '변환기'입니다.<br>
 * 이 클래스의 역할은 {@code coupon-delay-events} 토픽에서 메시지를 "받는 즉시"
 * {@code coupon-activate-events} 토픽으로 "보내는 것"이 <b>아니라</b>,
 *
 * <ol>
 * <li>메시지를 받으면, 메시지에 적힌 지연 시간(delayMillis)을 확인합니다.</li>
 * <li>메시지를 'StateStore'(내부 DB)에 "저장"합니다.</li>
 * <li>내부 '타이머'(Punctuator)를 사용해 1초마다 StateStore를 스캔합니다.</li>
 * <li>지연 시간이 만료된 이벤트를 발견하면, 그제서야 'StateStore'에서 꺼내어
 * 다음 토픽({@code coupon-activate-events})으로 "전송(forward)"합니다.</li>
 * </ol>
 *
 * <hr>
 * <b>Transformer 인터페이스 제네릭 의미:</b><br>
 * {@code Transformer<String, CouponDelayEvent, KeyValue<String, CouponDelayEvent>>}
 * <ul>
 * <li><b>Input Key:</b> {@code String} (쿠폰 ID)</li>
 * <li><b>Input Value:</b> {@code CouponDelayEvent} (수신한 이벤트)</li>
 * <li><b>Output:</b> {@code KeyValue<String, CouponDelayEvent>} (다음 토픽으로 보낼 이벤트)</li>
 * </ul>
 */
@Slf4j
public class CouponDelayTransformer implements Transformer<String, CouponDelayEvent, KeyValue<String, CouponDelayEvent>> {

  /**
   * 이 Transformer가 연결할 StateStore의 이름.
   * (CouponDelayStreamProcessor에서 정의한 "delay-events-store")
   */
  private final String stateStoreName;

  /**
   * Kafka Streams의 '컨텍스트'.
   * StateStore 접근, 타이머(Punctuator) 등록,
   * 메시지 수동 전송(forward) 등 저수준 API의 '다리' 역할을 하는 핵심 객체.
   */
  private ProcessorContext context;

  /**
   * 실제 '상태 저장소'(내부 DB) 인스턴스.
   * Key: Long (이벤트가 실행될 시간 - 초 단위 타임스탬프)
   * Value: CouponDelayEventListWrapper (해당 초에 실행될 이벤트 목록)
   */
  private KeyValueStore<Long, CouponDelayEventListWrapper> stateStore;

  /**
   * 생성자.
   * @param stateStoreName (CouponDelayStreamProcessor에서) 연결할 StateStore의 이름을 주입받음.
   */
  public CouponDelayTransformer(String stateStoreName) {
    this.stateStoreName = stateStoreName;
  }

  /**
   * <h3>[1단계] Transformer 초기화 (스트림 태스크당 1회 실행)</h3>
   *
   * 이 Transformer 인스턴스가 생성되고 스트림 태스크에 할당될 때 호출됩니다.
   * (Spring의 @PostConstruct와 유사)
   *
   * @param context Kafka Streams가 주입해주는 프로세서 컨텍스트
   */
  @Override
  public void init(ProcessorContext context) {
    // 1. 컨텍스트와 StateStore를 멤버 변수에 할당
    this.context = context;
    this.stateStore = context.getStateStore(stateStoreName);

    // 2. [핵심] 'Punctuator'(타이머)를 스케줄링합니다.
    this.context.schedule(
            Duration.ofSeconds(1),          // "1초" 마다
            PunctuationType.WALL_CLOCK_TIME, // "실제 세계의 시간(시스템 시간)"을 기준으로
            this::checkStoreAndForward      // "checkStoreAndForward" 메소드를 실행하라
    );
    // (참고) PunctuationType.STREAM_TIME: Kafka 메시지 내부의 타임스탬프를 기준 (데이터 처리용)
    //        PunctuationType.WALL_CLOCK_TIME: 실제 시간 기준 (지연 큐/알림용)
  }

  /**
   * <h3>[2단계] 메시지 수신 (메시지마다 1회 실행)</h3>
   *
   * {@code coupon-delay-events} 토픽에서 메시지 1건을 수신할 때마다 호출됩니다.
   *
   * @param key   메시지 Key
   * @param event 메시지 Value (CouponDelayEvent 객체)
   * @return 다음 토픽으로 보낼 메시지 (여기서는 'null'을 반환)
   */
  @Override
  public KeyValue<String, CouponDelayEvent> transform(String key, CouponDelayEvent event) {
    if (event == null || event.getDelayMillis() <= 0) {
      log.warn("잘못된 지연 이벤트 수신 (무시): {}", event);
      return null; // 처리할 필요 없으므로 즉시 종료
    }

    // 4. 이벤트가 "미래의 언젠가" 실행되어야 할 '절대 시간'을 계산합니다.
    //    (예: 현재시간 10:00 + 지연시간 30초 = 실행시간 10:30)
    //    (주의) context.currentSystemTimeMs()는 Kafka Streams가 인지하는 시간이므로,
    //         실제 외부 시간을 기준으로 하려면 Instant.now().toEpochMilli()가 더 정확할 수 있습니다.
    //    (코드 원본을 존중하여 context.currentSystemTimeMs() 사용)
    long executionTimestamp = context.currentSystemTimeMs() + event.getDelayMillis();

    // 5. StateStore에 <Key, Value> 형태로 저장
    //    Key는 '초' 단위로 그룹화합니다. (밀리초 단위로 저장하면 Key가 너무 많아짐)
    long executionSecond = executionTimestamp / 1000;

    // 5-1. StateStore에서 "해당 초(Key)"에 이미 저장된 이벤트 목록(Value)이 있는지 조회
    CouponDelayEventListWrapper wrapper = stateStore.get(executionSecond);
    if (wrapper == null) {
      // 5-2. 없다면, 새로운 목록(Wrapper)을 생성
      wrapper = new CouponDelayEventListWrapper(new ArrayList<>());
    }

    // 5-3. 현재 이벤트를 해당 목록에 추가
    wrapper.getEvents().add(event);

    // 5-4. <Key(실행 시간-초), Value(이벤트 목록)>을 StateStore에 덮어쓰기(저장)
    stateStore.put(executionSecond, wrapper);

    log.debug("이벤트 저장 완료 (지연 대기 시작): Key={}, 실행 예정 시각={}",
            key, Instant.ofEpochMilli(executionTimestamp));

    // 6. [매우 중요] 'null'을 반환합니다.
    //    이는 Kafka Streams에게 "이 메시지를 지금 당장 다음 토픽으로 보내지 말라"는
    //    신호입니다. 메시지는 'StateStore'에 저장만 되고 '삼켜집니다'.
    //    (나중에 타이머가 직접 꺼내서 보낼 것입니다.)
    return null;
  }

  /**
   * <h3>[3단계] 타이머 실행 (init에서 등록한 스케줄에 따라 1초마다 실행)</h3>
   *
   * init()에서 등록한 'schedule'에 의해 1초마다 주기적으로 호출됩니다.
   *
   * @param currentTimestamp 타이머가 실행되는 '현재 시간' (WALL_CLOCK_TIME 기준)
   */
  private void checkStoreAndForward(long currentTimestamp) {
    long currentSecond = currentTimestamp / 1000; // 현재 시간을 '초' 단위로 변환

    // 8. [핵심] StateStore를 '범위 조회(range scan)'합니다.
    //    Key(실행 시간-초)가 0 (과거) 부터 "현재 시간(초)" 까지인
    //    모든 데이터를 조회합니다.
    //    -> "지금 당장 실행해야 하거나" 혹은 "과거에 실행됐어야 했는데 놓친"
    //       모든 이벤트 목록을 가져옵니다.
    try (KeyValueIterator<Long, CouponDelayEventListWrapper> iter = stateStore.range(0L, currentSecond)) {

      while (iter.hasNext()) {
        // 8-1. 실행 시간이 도래한 항목(Key-Value)을 하나씩 꺼냅니다.
        KeyValue<Long, CouponDelayEventListWrapper> entry = iter.next();
        Long executionSecond = entry.key; // 실행 시간(초)
        CouponDelayEventListWrapper wrapper = entry.value; // 해당 초에 실행될 이벤트 목록

        // 9. 목록(Wrapper)에 포함된 모든 이벤트를 다음 토픽으로 "수동 전송"합니다.
        for (CouponDelayEvent event : wrapper.getEvents()) {

          // [매우 중요] context.forward():
          // 'transform'에서 null을 반환하여 보류했던 메시지를
          // 지금 이 시점에 '수동으로' 다음 토픽(coupon-activate-events)으로
          // 보냅니다. (지연 처리 완료!)
          context.forward(String.valueOf(event.getCouponId()), event);

          log.info("[Kafka Streams Timer] 쿠폰 {} 활성화 이벤트 발행 (지연 실행 완료)", event.getCouponId());
        }

        // 10. [매우 중요] 처리가 완료된 항목은 StateStore에서 "삭제"합니다.
        //     이걸 하지 않으면 1초 뒤에 또 조회되어 중복 처리됩니다.
        stateStore.delete(executionSecond);
      }
    }
  }

  /**
   * <h3>[4단계] 종료 (스트림 태스크 종료 시 1회 실행)</h3>
   *
   * 스트림 애플리케이션이 종료될 때 호출됩니다.
   * (필요시 StateStore 연결 해제 등 리소스 정리)
   */
  @Override
  public void close() {
    // 리소스 정리 (현재 코드에서는 특별히 할 것 없음)
  }

  /**
   * StateStore에 List<CouponDelayEvent>를 직접 저장하기 위한 Wrapper 클래스.
   * (이유: Java 제네릭의 타입 소거 문제로 인한 Serde(직렬화) 오류 방지)
   * (Lombok @Data, @NoArgsConstructor, @AllArgsConstructor가
   * Getter/Setter/기본생성자를 자동으로 만들어주어 Serde가 정상 동작합니다.)
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CouponDelayEventListWrapper {
    private List<CouponDelayEvent> events;
  }
}