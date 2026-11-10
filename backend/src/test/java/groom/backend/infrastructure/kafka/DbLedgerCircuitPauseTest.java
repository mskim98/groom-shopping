package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.PaymentApplicationService;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import groom.backend.infrastructure.resilience.DbLedgerCircuit;
import groom.backend.infrastructure.resilience.DbLedgerPauseErrorHandler;
import groom.backend.infrastructure.resilience.SharedCircuitFlag;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.util.backoff.FixedBackOff;

// db-ledger 회로가 OPEN 이면(자기 상태든 공유 키든) 결제 컨슈머 컨테이너를 멈추고, 풀리면 재개하며, 멈춘 사이 도착한 레코드는 승인하지 않고 재개 뒤 처리한다
// Kafka 는 이 시험이 띄운 내장 브로커다 - 공용 9092 의 payment-event-group 을 건드리지 않는다
@DisplayName("db-ledger 회로와 결제 컨슈머 일시정지")
class DbLedgerCircuitPauseTest {

    private static final String GROUP = "t3-db-ledger-pause-" + UUID.randomUUID();

    private final ObjectMapper mapper = new ObjectMapper();
    private final PaymentApplicationService service = mock(PaymentApplicationService.class);
    private final AtomicReference<String> sharedValue = new AtomicReference<>();
    private final AtomicBoolean dbDown = new AtomicBoolean();
    private final List<UUID> processed = new CopyOnWriteArrayList<>();

    private EmbeddedKafkaBroker broker;
    private ConcurrentMessageListenerContainer<String, String> container;
    private CircuitBreaker breaker;
    private DbLedgerCircuit circuit;
    // 운영의 @Scheduled(payment.consumer.circuit-check-ms) 와 같은 일을 100ms 간격으로 한다
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void setUp() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, "payment-events");
        broker.afterPropertiesSet();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        breaker = registry.circuitBreaker(DbLedgerCircuit.NAME, CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(600)).automaticTransitionFromOpenToHalfOpenEnabled(true)
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(DataAccessResourceFailureException.class).build());

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(SharedCircuitFlag.key(DbLedgerCircuit.NAME))).thenAnswer(call -> sharedValue.get());
        SharedCircuitFlag flag = new SharedCircuitFlag(redis, registry, List.of());

        circuit = new DbLedgerCircuit(registry, flag, null);
        doAnswer(call -> {
            if (dbDown.get()) {
                throw new DataAccessResourceFailureException("db down");
            }
            processed.add(((PaymentApprovedEvent) call.getArgument(0)).paymentId());
            return null;
        }).when(service).applyApprovedEvent(any());

        PaymentEventConsumer consumer = new PaymentEventConsumer(service, mapper);
        consumer.setDbLedgerCircuit(circuit);

        Map<String, Object> props = KafkaTestUtils.consumerProps(GROUP, "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        ContainerProperties containerProps = new ContainerProperties("payment-events");
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        containerProps.setMessageListener((MessageListener<String, String>) consumer::consume);
        container = new ConcurrentMessageListenerContainer<>(new DefaultKafkaConsumerFactory<>(props), containerProps);
        container.setCommonErrorHandler(new DbLedgerPauseErrorHandler(new FixedBackOff(50L, 20L)));
        container.start();
        ticker.scheduleWithFixedDelay(() -> circuit.checkOnce(container), 100, 100, TimeUnit.MILLISECONDS);
    }

    @AfterEach
    void tearDown() {
        ticker.shutdownNow();
        container.stop();
        broker.destroy();
    }

    private UUID send() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentApprovedEvent event = new PaymentApprovedEvent(paymentId, UUID.randomUUID(), 1000,
                new TossPaymentResponse(), List.of());
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        props.put("key.serializer", StringSerializer.class);
        props.put("value.serializer", StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("payment-events", paymentId.toString(), mapper.writeValueAsString(event))).get();
        }
        return paymentId;
    }

    private long committed() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
            Map<TopicPartition, OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
            return offsets.values().stream().mapToLong(OffsetAndMetadata::offset).sum();
        }
    }

    @Test
    @DisplayName("데이터 접근 실패로 회로가 OPEN 이면 컨테이너를 멈추고, 멈춘 사이 레코드는 승인하지 않으며, 회로와 키가 모두 풀리면 재개해 순서대로 처리한다")
    void OPEN_컨테이너를_멈추고_풀리면_재개해_처리한다() throws Exception {
        dbDown.set(true);
        UUID first = send();

        await().atMost(Duration.ofSeconds(15)).until(() -> breaker.getState() == CircuitBreaker.State.OPEN
                || breaker.getState() == CircuitBreaker.State.HALF_OPEN);
        await().atMost(Duration.ofSeconds(15)).until(container::isPauseRequested);
        UUID second = send();
        // 공유 키도 살아 있게 해 둔다 - 자기 회로가 HALF_OPEN 이 돼도 키가 사라질 때까지 멈춰 있어야 한다
        sharedValue.set(String.valueOf(System.currentTimeMillis() + 60_000));
        dbDown.set(false);

        await().atMost(Duration.ofSeconds(5)).until(() -> breaker.getState() == CircuitBreaker.State.HALF_OPEN);
        Thread.sleep(500);
        assertThat(container.isPauseRequested()).as("키가 살아 있는 동안 멈춤").isTrue();
        assertThat(processed).as("멈춘 사이 처리된 레코드").isEmpty();
        assertThat(committed()).as("승인된 오프셋").isZero();

        sharedValue.set(null);

        await().atMost(Duration.ofSeconds(15)).until(() -> processed.size() == 2);
        assertThat(processed).containsExactly(first, second);
        assertThat(container.isPauseRequested()).isFalse();
    }

    @Test
    @DisplayName("회로가 닫혀 있어도 공유 키가 살아 있으면 멈추고, 키가 사라지면 최대 확인 주기 안에 재개한다")
    void 공유키만_살아있어도_멈추고_사라지면_재개한다() {
        MessageListenerContainer fake = mock(MessageListenerContainer.class);
        AtomicBoolean paused = new AtomicBoolean();
        when(fake.isPauseRequested()).thenAnswer(call -> paused.get());
        doAnswer(call -> {
            paused.set(true);
            return null;
        }).when(fake).pause();
        doAnswer(call -> {
            paused.set(false);
            return null;
        }).when(fake).resume();
        sharedValue.set(String.valueOf(System.currentTimeMillis() + 60_000));

        circuit.checkOnce(fake);
        circuit.checkOnce(fake);

        verify(fake, times(1)).pause();
        sharedValue.set(null);
        circuit.checkOnce(fake);
        verify(fake, times(1)).resume();
        circuit.checkOnce(fake);
        verify(fake, times(1)).resume();
        verify(fake, never()).stop();
    }

    @Test
    @DisplayName("회로 OPEN 중 run 은 작업을 실행하지 않고 CallNotPermittedException 을 낸다")
    void OPEN_작업을_실행하지_않는다() {
        breaker.transitionToOpenState();
        AtomicBoolean ran = new AtomicBoolean();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> circuit.run(() -> ran.set(true)))
                .isInstanceOf(CallNotPermittedException.class);

        assertThat(ran).isFalse();
    }
}
