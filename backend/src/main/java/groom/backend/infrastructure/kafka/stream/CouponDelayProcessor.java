package groom.backend.infrastructure.kafka.stream;

import java.time.Duration;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * 쿠폰 지연 비활성화 처리기 (Kafka Streams 신 Processor API).
 * <p>
 * 입력 토픽의 이벤트를 즉시 내보내지 않고 StateStore 에 '실행 예정 초' 를 키로 적재한 뒤,
 * WALL_CLOCK_TIME punctuator 가 1초마다 도래한 건만 출력 토픽으로 forward 한다.
 * WALL_CLOCK 을 쓰는 이유는 STREAM_TIME 이 신규 레코드 없이는 전진하지 않아,
 * 유입이 멈춘 구간에서 대기 중인 쿠폰이 영원히 비활성화되지 않기 때문이다.
 */
@Slf4j
public class CouponDelayProcessor implements Processor<String, CouponDelayEvent, String, CouponDelayEvent> {

  private final String stateStoreName;

  private ProcessorContext<String, CouponDelayEvent> context;
  private KeyValueStore<Long, CouponDelayEventListWrapper> stateStore;

  public CouponDelayProcessor(String stateStoreName) {
    this.stateStoreName = stateStoreName;
  }

  @Override
  public void init(ProcessorContext<String, CouponDelayEvent> context) {
    this.context = context;
    this.stateStore = context.getStateStore(stateStoreName);
    this.context.schedule(
            Duration.ofSeconds(1),
            PunctuationType.WALL_CLOCK_TIME,
            this::checkStoreAndForward);
  }

  @Override
  public void process(Record<String, CouponDelayEvent> record) {
    CouponDelayEvent event = record.value();
    if (event == null || event.getDelayMillis() == null || event.getDelayMillis() <= 0) {
      log.warn("잘못된 지연 이벤트 수신 (무시): {}", event);
      return;
    }

    // 실행 예정 시각을 '초' 로 그룹화한다. 밀리초로 두면 키가 과도하게 늘어난다
    long executionSecond = (context.currentSystemTimeMs() + event.getDelayMillis()) / 1000;

    CouponDelayEventListWrapper wrapper = stateStore.get(executionSecond);
    if (wrapper == null) {
      wrapper = new CouponDelayEventListWrapper(new ArrayList<>());
    }
    // 같은 초의 이벤트는 목록에 누적한다 - 덮어쓰면 유실된다
    wrapper.getEvents().add(event);
    stateStore.put(executionSecond, wrapper);
  }

  // 도래한 초만 range 로 훑어 forward 하고 즉시 지운다. 지우지 않으면 다음 초에 중복 처리된다
  private void checkStoreAndForward(long wallClockTime) {
    long currentSecond = wallClockTime / 1000;
    try (KeyValueIterator<Long, CouponDelayEventListWrapper> iter = stateStore.range(0L, currentSecond)) {
      while (iter.hasNext()) {
        KeyValue<Long, CouponDelayEventListWrapper> entry = iter.next();
        for (CouponDelayEvent event : entry.value.getEvents()) {
          context.forward(new Record<>(String.valueOf(event.getCouponId()), event, wallClockTime));
          log.info("[Kafka Streams Timer] 쿠폰 {} 활성화 이벤트 발행 (지연 실행 완료)", event.getCouponId());
        }
        stateStore.delete(entry.key);
      }
    }
  }

  @Override
  public void close() {
  }
}
