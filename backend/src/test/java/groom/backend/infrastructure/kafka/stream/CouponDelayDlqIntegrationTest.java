package groom.backend.infrastructure.kafka.stream;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.infrastructure.product.StockWarmUpRunner;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 쿠폰 지연 처리 DLQ 갭 통합 테스트 (실 Kafka 필요).
 *
 * <p>검증 대상은 메인 컨슈머에 적용된 표준 에러핸들러 — {@code couponDelayEventConcurrentKafkaListenerContainerFactory}
 * 의 {@code DefaultErrorHandler}(지수 백오프) + {@code DeadLetterPublishingRecoverer}(→ {@code coupon-delay-dlq}).
 *
 * <p>실행 중인 앱이 동일 그룹({@code coupon-delay-group})으로 운영 토픽을 함께 구독하면 메시지를 가로채
 * 비결정적이 되므로, 같은 팩토리 빈으로 <b>고유 그룹 + 전용 소스 토픽 + 강제 실패 리스너</b>를 직접 구성해 격리한다.
 */
// 앞 테스트의 캐시된 컨텍스트가 Kafka Streams 상태 디렉터리와 coupon-delay 리스너를 붙들고 있으면
// 이 테스트의 전용 그룹이 메시지를 나눠 받아 비결정적이 된다
// BEFORE_CLASS 로 직전 컨텍스트를 닫고 새로 띄운다
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = "spring.kafka.streams.auto-startup=false")
@DisplayName("쿠폰 지연 DLQ 이관 통합 테스트")
class CouponDelayDlqIntegrationTest {

    private static final String SOURCE_TOPIC = "coupon-delay-dlq-test-source";
    private static final String DLQ_TOPIC = "coupon-delay-dlq";

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    @Qualifier("couponDelayEventConcurrentKafkaListenerContainerFactory")
    private ConcurrentKafkaListenerContainerFactory<String, CouponDelayEvent> listenerContainerFactory;

    @Autowired
    @Qualifier("couponDelayEventKafkaTemplate")
    private KafkaTemplate<String, CouponDelayEvent> couponDelayEventKafkaTemplate;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Test
    @DisplayName("리스너가 계속 실패하면 지수 백오프 재시도 후 coupon-delay-dlq 로 이관된다")
    void failedConsume_routesToDlq() {
        long couponId = Math.abs(UUID.randomUUID().getMostSignificantBits());

        // given - DLQ 컨슈머 먼저 구독(이후 발행분만 수신)
        Consumer<String, String> dlqConsumer = createConsumer("coupon-dlq-recv-" + UUID.randomUUID());
        subscribeAtEnd(dlqConsumer, DLQ_TOPIC);

        // given - 실제 팩토리(에러핸들러 동일)로 고유 그룹 + 강제 실패 리스너 컨테이너 구성
        ConcurrentMessageListenerContainer<String, CouponDelayEvent> container =
                listenerContainerFactory.createContainer(SOURCE_TOPIC);
        container.getContainerProperties().setGroupId("coupon-delay-test-" + UUID.randomUUID());
        // 소스 토픽은 실행마다 메시지가 쌓인다. 새 그룹이 earliest 로 시작하면 옛 메시지부터 실패시키는데,
        // 실패 1건마다 백오프 7초가 붙어 20초 안에 이번 메시지에 도달하지 못한다
        // (증상: DLQ 에는 옛 couponId 가 적재되고 이 테스트는 자기 couponId 를 못 찾아 실패한다)
        container.getContainerProperties().getKafkaConsumerProperties()
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        container.getContainerProperties().setMessageListener(
                (MessageListener<String, CouponDelayEvent>) record -> {
                    throw new RuntimeException("강제 실패(테스트)");
                });

        try {
            container.start();
            ContainerTestUtils.waitForAssignment(container, 1);

            // when - 전용 소스 토픽으로 발행 → 리스너가 계속 실패
            couponDelayEventKafkaTemplate.send(SOURCE_TOPIC, String.valueOf(couponId),
                    new CouponDelayEvent(couponId, 0L, System.currentTimeMillis()));

            // then - 지수 백오프(1s·2s·4s, maxElapsed 7s) 후 DLQ 이관 (여유 있게 20s 대기)
            boolean received = pollFor(dlqConsumer, Duration.ofSeconds(20), record ->
                    record.value() != null && record.value().contains("\"couponId\":" + couponId));
            assertThat(received).as("coupon-delay-dlq 에 실패 이벤트 수신").isTrue();
        } finally {
            container.stop();
            dlqConsumer.close();
        }
    }

    /** timeout 동안 폴링하며 조건에 맞는 레코드가 오면 true. */
    private boolean pollFor(Consumer<String, String> consumer, Duration timeout,
                            java.util.function.Predicate<ConsumerRecord<String, String>> match) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (match.test(record)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Consumer<String, String> createConsumer(String group) {
        var props = KafkaTestUtils.consumerProps(bootstrapServers, group, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }

    /** 구독 후 파티션 할당을 기다려 오프셋을 끝(latest)으로 이동 → 이후 발행분만 수신. */
    private void subscribeAtEnd(Consumer<String, String> consumer, String topic) {
        consumer.subscribe(List.of(topic));
        for (int i = 0; i < 20 && consumer.assignment().isEmpty(); i++) {
            consumer.poll(Duration.ofMillis(100));
        }
        consumer.seekToEnd(consumer.assignment());
        consumer.poll(Duration.ofMillis(0));
    }
}
