package groom.backend.infrastructure.kafka.stream;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.List;

/**
 * <h3>Kafka Streams 토폴로지(Topology) 정의 클래스</h3>
 *
 * Kafka Streams의 '데이터 처리 흐름'을 정의하는 Spring 설정 클래스입니다.<br>
 * 이 클래스는 Processor API(저수준 API)를 사용하여 쿠폰 지연 이벤트를 처리합니다.
 *
 * <hr>
 * <h4>핵심 동작 원리:</h4>
 * <ol>
 * <li><b>[INPUT_TOPIC]</b>에서 지연 이벤트(CouponDelayEvent)를 수신합니다.</li>
 * <li><b>[CouponDelayTransformer]</b>를 사용하여 이벤트를 즉시 처리하지 않고,
 * <b>[StateStore]</b>라는 Kafka Streams 내부의 소형 데이터베이스에 저장합니다.</li>
 * <li>(Transformer 내부의 'Punctuator'(타이머)가 주기적으로 StateStore를 스캔합니다.)</li>
 * <li>(지연 시간이 만료된 이벤트를 StateStore에서 발견하면...)</li>
 * <li><b>[OUTPUT_TOPIC]</b>으로 해당 이벤트를 발행(Forward)합니다.</li>
 * </ol>
 * <hr>
 *
 * <b>@EnableKafkaStreams</b>: Spring Boot에게 Kafka Streams 기능을 활성화하도록 지시합니다.<br>
 * 이 어노테이션이 있어야 Spring이 자동으로 {@link StreamsBuilder} 빈(Bean)을 생성하고,
 * 이 클래스에서 정의한 스트림(@Bean)을 시작 및 관리합니다.
 */
@Slf4j
@Configuration
@EnableKafkaStreams
public class CouponDelayStreamProcessor {

  // 1. 입력 토픽: Producer가 지연 이벤트를 발행하는 토픽
  private static final String INPUT_TOPIC = "coupon-delay-events";

  // 2. 출력 토픽: 지연 시간이 만료된 이벤트를 발행할 토픽 (이후 Consumer가 구독)
  private static final String OUTPUT_TOPIC = "coupon-activate-events";

  // 3. 상태 저장소(StateStore)의 이름: 지연 이벤트를 임시 보관할 '내부 DB'의 이름
  private static final String DELAY_STATE_STORE = "delay-events-store";

  /**
   * Kafka Streams의 '토폴로지(Topology)'를 Bean으로 등록합니다.
   * Spring이 시작될 때 이 메소드가 실행되어 데이터 흐름이 정의됩니다.
   *
   * @param builder {@literal @EnableKafkaStreams} 어노테이션에 의해 Spring이
   * 자동으로 주입해주는 '스트림 설계자'입니다.
   * @return 정의된 KStream 객체 (Spring이 이 스트림을 관리합니다)
   */
  @Bean
  public KStream<String, CouponDelayEvent> couponDelayStream(StreamsBuilder builder) {

    // --- [STEP 1] Serde (직렬화/역직렬화기) 정의 ---
    // Serde = Serializer + Deserializer
    // Kafka는 데이터를 byte[]로만 주고받습니다.
    // Java 객체(CouponDelayEvent) <-> byte[] (JSON) 변환기가 필요합니다.

    // 1. CouponDelayEvent 객체용 Serde
    Serde<CouponDelayEvent> couponDelayEventSerde = Serdes.serdeFrom(
            new JsonSerializer<>(), // Java -> JSON(byte[])
            new JsonDeserializer<>(CouponDelayEvent.class) // JSON(byte[]) -> Java
    );

    // 2. StateStore에 저장될 'List 래퍼(Wrapper)' 객체용 Serde
    Serde<CouponDelayEventListWrapper> wrapperSerde = Serdes.serdeFrom(
            new JsonSerializer<>(),
            new JsonDeserializer<>(CouponDelayEventListWrapper.class)
    );

    // --- [STEP 2] StateStore (상태 저장소) 정의 ---
    // '지연 중인' 이벤트들을 임시로 저장할 '데이터베이스'를 정의합니다.
    // <Key, Value> 형식의 저장소입니다.

    // 3. KeyValueStore를 생성하기 위한 '설계도(Builder)'
    StoreBuilder<KeyValueStore<Long, CouponDelayEventListWrapper>> storeBuilder = Stores.keyValueStoreBuilder(

            // StateStore를 디스크 기반의 '영속성' 저장소로 설정합니다 (앱이 재시작되어도 유지됨)
            // (Stores.inMemoryKeyValueStore(...)를 사용하면 메모리 기반이 됨)
            Stores.persistentKeyValueStore(DELAY_STATE_STORE), // 저장소 이름

            Serdes.Long(), // Key 타입: Long (이벤트 실행 시간 '초(second)' 단위)
            wrapperSerde   // Value 타입: CouponDelayEventListWrapper (해당 초에 실행될 이벤트 목록)
    );

    // 4. 정의한 StateStore '설계도'를 'StreamsBuilder'에 등록합니다.
    //    이제 이 토폴로지 내의 Transformer들이 'DELAY_STATE_STORE'라는 이름으로
    //    이 저장소에 접근할 수 있게 됩니다.
    builder.addStateStore(storeBuilder);

    // --- [STEP 3] 스트림 토폴로지 (데이터 흐름) 정의 ---

    // 5. INPUT_TOPIC에서 KStream(데이터의 강)을 시작합니다.
    KStream<String, CouponDelayEvent> stream = builder.stream(
            INPUT_TOPIC, // 구독할 토픽
            Consumed.with(Serdes.String(), couponDelayEventSerde) // 토픽의 Key/Value Serde 지정
    );

    // 6. [핵심] 'transform' 오퍼레이터 연결
    //    'transform'은 .map()이나 .filter() 같은 고수준 DSL과 달리,
    //    'StateStore' 접근, '시간 기반'(Punctuator) 스케줄링 등
    //    저수준의 복잡한 커스텀 로직을 수행할 수 있게 해줍니다.
    stream
            .transform(
                    // 이 팩토리 람다는 스트림 '태스크'가 생성될 때마다
                    // 'CouponDelayTransformer'의 새 인스턴스를 생성합니다.
                    // 'DELAY_STATE_STORE' 이름을 생성자로 넘겨주어,
                    // Transformer가 어떤 StateStore를 사용할지 알려줍니다.
                    () -> new CouponDelayTransformer(DELAY_STATE_STORE),

                    // [중요] 이 Transformer가 'DELAY_STATE_STORE'라는 이름의
                    // StateStore에 '접근해야 함'을 명시적으로 선언합니다.
                    // (위의 builder.addStateStore(storeBuilder)와 연결됩니다.)
                    DELAY_STATE_STORE
            )
            // 7. [출력] Transformer가 지연 처리 후 'forward'한 메시지를
            //    OUTPUT_TOPIC으로 보냅니다.
            .to(
                    OUTPUT_TOPIC,
                    // 출력 토픽에 저장할 때 사용할 Key/Value Serde 지정
                    Produced.with(Serdes.String(), couponDelayEventSerde)
            );

    log.info("Kafka Streams [Processor API (Timer)] 기반 지연 처리 파이프라인 등록 완료");
    return stream;
  }

  /**
   * <h3>StateStore 저장용 래퍼(Wrapper) 클래스</h3>
   *
   * Kafka StateStore에 {@code List<CouponDelayEvent>}를 직접 저장할 때 발생하는
   * Java '타입 소거(Type Erasure)' 문제를 피하기 위해 사용하는 '포장' 클래스입니다.
   *
   * <ol>
   * <li><b>기술적 이유 (직렬화):</b>
   * Java의 제네릭(List<T>)은 런타임에 타입 정보(T)가 사라집니다.
   * JsonDeserializer가 JSON 데이터를 {@code List<CouponDelayEvent>}로
   * 정확히 변환하지 못할 수 있습니다.
   * 이처럼 구체적인 클래스(Wrapper)로 감싸면, Deserializer가
   * 'CouponDelayEventListWrapper' 클래스를 명확히 인지하고,
   * 내부의 'events' 필드에 List 데이터를 안전하게 채워 넣을 수 있습니다.
   * </li>
   * <li><b>논리적 이유 (데이터 구조):</b>
   * StateStore의 Key는 '실행 시간(초)'입니다.
   * 만약 '같은 1초' 안에 여러 개의 이벤트가 실행되어야 한다면,
   * Key 하나(예: 1678888800)에 여러 이벤트(Value)를 '목록(List)'으로
   * 저장해야 합니다. 이 클래스는 그 목록을 담는 '그릇' 역할을 합니다.
   * </li>
   * </ol>
   *
   * <b>[주의]</b> 이 클래스를 JsonSerializer/JsonDeserializer가 올바르게 처리하려면
   * <b>기본 생성자(No-args constructor)</b>와 <b>Getter/Setter</b>가 반드시 필요합니다.
   * (예: Lombok의 {@literal @Data}, {@literal @NoArgsConstructor} 사용)
   */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CouponDelayEventListWrapper {
    private List<CouponDelayEvent> events;
  }
}