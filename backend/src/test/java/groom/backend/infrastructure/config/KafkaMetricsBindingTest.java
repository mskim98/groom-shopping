package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;

/**
 * 손으로 만든 Kafka 팩토리가 Micrometer 에 묶여 있다
 *
 * <p>팩토리를 직접 만들면 Boot 가 붙여 주는 {@code MicrometerProducerListener} 가 빠져
 * {@code kafka_producer_*} · {@code kafka_consumer_*} 지표가 Streams 클라이언트 것만 남는다
 * (쿠폰 2,000 건을 발급해도 produce · consume 지표가 0)
 */
@DisplayName("Kafka 팩토리 지표 연결")
class KafkaMetricsBindingTest {

    // 프로듀서 상한. 브로커가 멈췄을 때 send 가 막히는 시간(max.block.ms)과 전달 포기 시각(delivery.timeout.ms)
    private static final int MAX_BLOCK_MS_LIMIT = 5_000;
    private static final int DELIVERY_TIMEOUT_MS_LIMIT = 35_000;

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("spring.kafka.bootstrap-servers=localhost:9094").applyTo(context);
        context.registerBean(MeterRegistry.class, () -> new SimpleMeterRegistry());
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.register(KafkaConfig.class, CouponIssueKafkaConfig.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("모든 프로듀서 팩토리에 MicrometerProducerListener 가 붙어 있다")
    @SuppressWarnings("rawtypes")
    void producerFactories_모두_Micrometer_리스너가_있다() {
        Map<String, ProducerFactory> factories = context.getBeansOfType(ProducerFactory.class);

        // 쿠폰 발급 · 재고 임계 · 쿠폰 지연 · 결제 · 추첨 - 한 곳이 빠지면 개수가 줄어 여기서 걸린다
        assertThat(factories).hasSizeGreaterThanOrEqualTo(5);
        factories.forEach((name, factory) ->
                assertThat(((DefaultKafkaProducerFactory<?, ?>) factory).getListeners())
                        .as("프로듀서 팩토리 %s 의 리스너", name)
                        .anyMatch(l -> l instanceof MicrometerProducerListener));
    }

    @Test
    @DisplayName("모든 컨슈머 팩토리에 MicrometerConsumerListener 가 붙어 있다")
    @SuppressWarnings("rawtypes")
    void consumerFactories_모두_Micrometer_리스너가_있다() {
        Map<String, ConsumerFactory> factories = context.getBeansOfType(ConsumerFactory.class);

        assertThat(factories).hasSizeGreaterThanOrEqualTo(5);
        factories.forEach((name, factory) ->
                assertThat(((DefaultKafkaConsumerFactory<?, ?>) factory).getListeners())
                        .as("컨슈머 팩토리 %s 의 리스너", name)
                        .anyMatch(l -> l instanceof MicrometerConsumerListener));
    }

    @Test
    @DisplayName("모든 프로듀서가 max.block.ms · delivery.timeout.ms 상한과 멱등 전송을 가진다")
    @SuppressWarnings("rawtypes")
    void producerFactories_상한과_멱등_전송을_가진다() {
        context.getBeansOfType(ProducerFactory.class).forEach((name, factory) -> {
            Map<String, Object> props = ((DefaultKafkaProducerFactory<?, ?>) factory).getConfigurationProperties();
            assertThat(toLong(props.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)))
                    .as("%s max.block.ms", name).isPositive().isLessThanOrEqualTo(MAX_BLOCK_MS_LIMIT);
            assertThat(toLong(props.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)))
                    .as("%s delivery.timeout.ms", name).isPositive().isLessThanOrEqualTo(DELIVERY_TIMEOUT_MS_LIMIT);
            assertThat(props.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG))
                    .as("%s enable.idempotence", name).isEqualTo(true);
            // 설정이 서로 충돌하면(멱등 + acks=1 등) 브로커에 붙기 전에 기동 시 예외가 난다. 여기서 먼저 걸러 낸다
            new ProducerConfig(props);
        });
    }

    @Test
    @DisplayName("Streams 원천 토픽 coupon-delay-events 와 DLQ 토픽 coupon-issue-dlq 의 NewTopic 빈이 있다")
    void topics_원천_토픽과_DLQ_토픽이_빈으로_있다() {
        Set<String> names = context.getBeansOfType(NewTopic.class).values().stream()
                .map(NewTopic::name).collect(Collectors.toSet());

        // 원천 토픽이 없으면 Streams 가 INCOMPLETE_SOURCE_TOPIC_METADATA 로 스스로 종료하고 모든 kafka_* 지표가 사라진다
        assertThat(names).contains("coupon-delay-events", "coupon-issue-dlq");
    }

    private static long toLong(Object value) {
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }
}
