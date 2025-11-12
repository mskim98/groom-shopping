package groom.backend.infrastructure.config;

import groom.backend.infrastructure.kafka.StockThresholdEvent;
import groom.backend.infrastructure.kafka.stream.CouponDelayEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Kafka 관련 설정을 등록하는 클래스입니다.
 * Producer(발행자)와 Consumer(소비자)를 위한 핵심 구성 요소(Factory, Template)를
 * Spring Bean으로 등록합니다.
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, StockThresholdEvent> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, StockThresholdEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ConsumerFactory<String, StockThresholdEvent> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-group");
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, StockThresholdEvent> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, StockThresholdEvent> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * <h3>[쿠폰] Producer(발행자) 설정 팩토리 (Bean)</h3>
     *
     * Kafka Producer 인스턴스를 생성하는 데 필요한 설정값들을 정의하는 '팩토리'입니다.<br>
     * {@link groom.backend.infrastructure.kafka.stream.CouponDelayProducer}가 사용하는 {@link KafkaTemplate}을 생성할 때 이 팩토리가 참조됩니다.
     *
     * @return CouponDelayEvent 전용 ProducerFactory
     */
    @Bean
    public ProducerFactory<String, CouponDelayEvent> couponDelayEventProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();// 1. Kafka 브로커 접속 주소
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // 2. 메시지 Key 직렬화 방식: 메시지 키(String)를 Kafka로 전송 가능한 byte[]로 변환
        //    (StringSerializer: "couponId_123" -> [99, 111, ...])
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // 3. 메시지 Value 직렬화 방식: CouponDelayEvent 객체를 Kafka로 전송 가능한 byte[]로 변환
        //    (JsonSerializer: CouponDelayEvent 객체 -> "{'couponId':123, ...}" JSON 문자열 -> byte[])
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // 4. [중요] ACKS (데이터 보장 수준)
        //    "1": Leader 파티션에만 저장되면 '성공'으로 간주합니다. (기본값, 속도와 안정성 균형)
        //    "0": 전송만 하고 성공 여부 확인 안 함 (속도 빠름, 유실 가능)
        //    "all"(-1): Leader + 모든 Follower 파티션에 복제되어야 '성공' (안전, 속도 느림)
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");

        // 5. [중요] RETRIES (재시도 횟수)
        //    일시적인 네트워크 오류 등으로 전송 실패 시, 3번까지 자동으로 재시도합니다.
        //    (메시지 유실을 방지하는 중요한 옵션)
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * <h3>[쿠폰] KafkaTemplate (Bean)</h3>
     *
     * {@link groom.backend.infrastructure.kafka.stream.CouponDelayProducer}가 Kafka로 메시지를 '실제로' 보낼 때 사용하는 도구입니다.<br>
     * 위에서 정의한 {@code couponDelayEventProducerFactory} 설정을 기반으로 생성되며,
     * {@code CouponDelayProducer}에 {@literal @Autowired}로 주입됩니다.
     *
     * @return CouponDelayEvent 전용 KafkaTemplate
     */
    @Bean
    public KafkaTemplate<String, CouponDelayEvent> couponDelayEventKafkaTemplate() {
        // Factory Bean을 주입받아 Template을 생성합니다.
        return new KafkaTemplate<>(couponDelayEventProducerFactory());
    }

    /**
     * <h3>[쿠폰] Consumer(소비자) 설정 팩토리 (Bean)</h3>
     *
     * Kafka Consumer 인스턴스를 생성하는 데 필요한 설정값들을 정의하는 '팩토리'입니다.<br>
     * {@link groom.backend.infrastructure.kafka.stream.CouponDelayConsumer}의 {@literal @KafkaListener}가 동작할 때 필요한
     * '리스너 컨테이너 팩토리' (아래 Bean)를 생성할 때 이 팩토리가 참조됩니다.
     *
     * @return CouponDelayEvent 전용 ConsumerFactory
     */
    @Bean
    public ConsumerFactory<String, CouponDelayEvent> couponDelayEventConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        // 1. Kafka 브로커 접속 주소
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // 2. [중요] GROUP_ID: 컨슈머 그룹 ID
        //    (주석 원본의 'notification-group'을 사용했습니다.
        //     만약 'coupon-delay-group'으로 분리해야 한다면 이 값을 수정해야 합니다.)
        //    같은 그룹 ID를 가진 컨슈머들은 "하나의 팀"처럼 동작하여,
        //    토픽의 메시지를 "나눠서" 처리합니다. (중복 처리 방지, 스케일 아웃)
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-group"); // TODO: 주석 원본 확인 필요

        // 3. 메시지 Key 역직렬화 방식: Kafka의 byte[]를 String(메시지 키)으로 변환
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // 4. 메시지 Value 역직렬화 방식: Kafka의 byte[](JSON)를 CouponDelayEvent 객체로 변환
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // 5. [중요] JSON 역직렬화 신뢰 패키지 설정
        //    Spring은 보안상의 이유로 알 수 없는 패키지의 클래스로 역직렬화하는 것을 막습니다.
        //    "*"는 모든 패키지를 신뢰한다는 의미입니다. (개발 편의성)
        //    (보안 강화 시: "groom.backend.infrastructure.kafka.stream" 처럼 명시)
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        // 6. [중요] AUTO_OFFSET_RESET_CONFIG (오프셋 초기화 정책)
        //    "earliest": 만약 현재 그룹 ID가 Kafka에 기록된 "오프셋(어디까지 읽었는지)" 정보가
        //                없다면(예: 앱이 처음 실행되거나 그룹 ID가 바뀐 경우),
        //                토픽의 "가장 처음(가장 오래된)" 메시지부터 읽어옵니다.
        //    "latest": (기본값) 가장 "최신" 메시지(앱이 실행된 이후 오는 메시지)부터 읽습니다.
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    /**
     * <h3>[쿠폰] Kafka 리스너 컨테이너 팩토리 (Bean)</h3>
     *
     * {@literal @KafkaListener} 어노테이션(예: {@link groom.backend.infrastructure.kafka.stream.CouponDelayConsumer})이 붙은 메소드를
     * 실행하는 '컨테이너(리스너)'를 생성하는 팩토리입니다.<br>
     * 위에서 정의한 {@code couponDelayEventConsumerFactory} 설정을 기반으로 컨슈머를 생성하고,
     * 리스너의 동작 방식(예: AckMode)을 설정합니다.
     *
     * @return CouponDelayEvent 전용 ConcurrentKafkaListenerContainerFactory
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CouponDelayEvent> couponDelayEventConcurrentKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CouponDelayEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        // 1. 이 리스너 팩토리가 사용할 Consumer 설정을 연결합니다.
        factory.setConsumerFactory(couponDelayEventConsumerFactory());

        // 2. [중요] AckMode (메시지 처리 완료 '확인' 모드)
        //    ContainerProperties.AckMode.RECORD:
        //    @KafkaListener가 붙은 'consume' 메소드가 "성공적으로 실행(예외 없이 리턴)"되면,
        //    Spring Kafka가 해당 메시지의 '오프셋'을 Kafka에 '커밋(Commit)'합니다.
        //    (→ "이 메시지 처리 완료했음" 이라고 Kafka에 보고함)
        //    (만약 consume 메소드에서 예외가 발생하면, 이 메시지는 커밋되지 않고
        //     재시도되거나 에러 핸들러로 넘어갑니다.)
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        return factory;
    }
}



