package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.payment.event.PaymentCompensationDlqEvent;
import groom.backend.infrastructure.kafka.PaymentCompensationDlqProducer;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

/**
 * 7.2 보상 트랜잭션 GIVEN_UP → DLQ 이관 통합 테스트 (실 Kafka 필요).
 *
 * <p>GIVEN_UP 분기 판단(재시도 한도 초과 시 DLQ publish 1회 호출)은 {@code PaymentCompensationServiceTest}
 * 단위 테스트가 검증한다. 이 통합 테스트는 그 뒤 단계 — 실제 {@link PaymentCompensationDlqProducer}가
 * {@code payment-compensation-dlq} 토픽으로 직렬화·발행하고 컨슈머가 수신 가능한지(Kafka 왕복) — 를 검증한다.
 *
 * <p>(Payment→Order FK 체인 때문에 보상 서비스 풀스택 DB 경로 대신 발행 경로로 범위를 한정한다.)
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = "spring.kafka.streams.auto-startup=false")
@DisplayName("보상 GIVEN_UP DLQ 발행 통합 테스트(7.2)")
class PaymentCompensationDlqIntegrationTest {

    private static final String DLQ_TOPIC = "payment-compensation-dlq";

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private PaymentCompensationDlqProducer dlqProducer;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Test
    @DisplayName("GIVEN_UP 이벤트를 발행하면 payment-compensation-dlq 로 직렬화되어 수신된다")
    void publish_routesToDlq() {
        String paymentKey = "pk-" + UUID.randomUUID();
        PaymentCompensationDlqEvent event = new PaymentCompensationDlqEvent(
                UUID.randomUUID(), UUID.randomUUID(), paymentKey,
                1000, "DB 후처리 실패", "toss down", 5, LocalDateTime.now());

        try (Consumer<String, String> consumer = createDlqConsumer()) {
            // earliest + 고유 paymentKey 필터 → 할당 타이밍 레이스 없이 결정적으로 수신 확인
            consumer.subscribe(List.of(DLQ_TOPIC));

            // when - 실제 프로듀서가 DLQ 토픽으로 발행
            dlqProducer.publish(event);

            // then - DLQ 토픽에 해당 paymentKey 이벤트(key + JSON payload) 도착
            boolean received = pollFor(consumer, Duration.ofSeconds(10), record ->
                    paymentKey.equals(record.key()) && record.value().contains(paymentKey));
            assertThat(received).as("payment-compensation-dlq 에 GIVEN_UP 이벤트 수신").isTrue();
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

    private Consumer<String, String> createDlqConsumer() {
        var props = KafkaTestUtils.consumerProps(bootstrapServers, "dlq-test-" + UUID.randomUUID(), "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
