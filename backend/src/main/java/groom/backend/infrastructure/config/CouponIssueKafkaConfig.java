package groom.backend.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 쿠폰 발급 파이프라인 전용 Kafka 설정
 *
 * <p>결제 쪽 설정을 같이 쓰지 않고 갈라 둔다. 여기서 정하는 타임아웃은 <b>요청 스레드 점유 상한</b>이라
 * 결제 아웃박스 릴레이에 맞춘 값과 목적이 다르다. 한 곳에 묶으면 한쪽을 조일 때 다른 쪽이 함께 조여진다
 */
@Slf4j
@Configuration
public class CouponIssueKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // 파티션 수. 컨슈머 수의 상한이기도 하다
    @Value("${coupon.issue.partitions:4}")
    private int partitions;

    // 리스너 스레드 수. 파티션 수와 같게 둔다
    @Value("${coupon.issue.consumer-concurrency:4}")
    private int concurrency;

    // 한 번에 받아 한 문장으로 넣는 건수
    // 키우면 커넥션 획득 · 트랜잭션 · 왕복이 줄지만 INSERT · 인덱스 갱신 · WAL 은 그대로다
    // 그래서 어느 지점부터 이득이 멈추는지가 스윕의 측정 대상이다
    @Value("${coupon.issue.batch-size:500}")
    private int batchSize;

    /**
     * 요청 토픽 - 파티션을 미리 만든다
     *
     * <p>자동 생성에 맡기면 브로커 기본값(보통 1)로 만들어지고, 그러면 컨슈머를 4 개 띄워도
     * 배정받는 것은 1 개뿐이라 나머지 3 개가 논다. 그 상태의 측정값은 파티션 선형성과 무관해진다
     */
    @Bean
    public NewTopic couponIssueRequestTopic() {
        return TopicBuilder.name(CouponAsyncIssueService.REQUEST_TOPIC)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic couponIssueResultTopic() {
        return TopicBuilder.name("coupon-issue-results")
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    /**
     * 발급 요청 발행자
     *
     * <p>타임아웃이 이 설정의 본체다. 동기 방식인 한 <b>외부 호출 타임아웃이 곧 스레드 점유 상한</b>이고,
     * 상한이 없으면 브로커가 느려질 때 접수 스레드가 그만큼 붙잡힌다
     * <ul>
     *   <li>{@code max.block.ms} - 버퍼가 차거나 메타데이터를 못 받을 때 {@code send} 가 막히는 상한</li>
     *   <li>{@code delivery.timeout.ms} - 재시도까지 포함한 전달 포기 시각. 이 시각에 콜백이 실패로 돌아와
     *       배정한 자리를 되돌린다. 없으면 배정만 되고 아무도 확정하지 않는 자리가 쌓인다</li>
     * </ul>
     *
     * <p>{@code acks=all} 로 두는 이유 : 메시지 한 건이 사라지면 게이트를 통과한 자리가 확정되지 않고,
     * 되돌릴 계기도 없어 그 수량은 아무도 받지 못한 채 남는다
     */
    @Bean
    public ProducerFactory<String, String> couponIssueProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        // delivery.timeout.ms >= linger.ms + request.timeout.ms 라야 기동 시 검증을 통과한다
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> couponIssueKafkaTemplate() {
        return new KafkaTemplate<>(couponIssueProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, String> couponIssueConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, CouponAsyncIssueService.CONSUMER_GROUP);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, batchSize);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * 배치 리스너 컨테이너
     *
     * <p>{@code AckMode.BATCH} - 배치를 예외 없이 끝냈을 때만 오프셋을 커밋한다
     * 중간에 예외가 나면 커밋되지 않아 같은 배치가 다시 오고, 이미 들어간 행은
     * {@code ON CONFLICT DO NOTHING} 이 건너뛴다
     *
     * <p>재시도를 유한하게 끊는 이유 : 무한 재시도는 그 파티션의 뒤 이벤트를 영원히 막는다
     * 다 쓰고도 실패하면 배정한 자리를 되돌리고 실패로 기록한 뒤 넘어간다 -
     * 되돌리지 않으면 그 수량은 아무도 받지 못한 채 사라진다
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> couponIssueBatchListenerContainerFactory(
            ObjectProvider<CouponAsyncIssueService> asyncIssueServiceProvider, ObjectMapper objectMapper) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(couponIssueConsumerFactory());
        factory.setBatchListener(true);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    // 배치 리스너에서 재시도를 다 쓰면 이 복구자가 배치의 레코드마다 한 번씩 불린다
                    try {
                        CouponIssueRequestEvent event = objectMapper.readValue(
                                String.valueOf(record.value()), CouponIssueRequestEvent.class);
                        asyncIssueServiceProvider.getObject().abandonBatch(List.of(event), exception);
                    } catch (Exception e) {
                        log.error("[COUPON_BATCH_RECOVER_FAILED] payload={}, error={}",
                                record.value(), e.getMessage());
                    }
                },
                new FixedBackOff(1000L, 3L));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
