package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.payment.event.PaymentEventParseException;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import java.util.function.BiFunction;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

// 결제 승인 컨슈머 전용 컨테이너 팩토리와 토픽 설정을 빈으로 꺼내 확인한다(공용 팩토리는 쿠폰 컨슈머가 쓴다)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("결제 Kafka 설정 빈")
class PaymentKafkaConfigTest {

    private static final String PAYMENT_FACTORY = "paymentApprovedKafkaListenerContainerFactory";

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private ApplicationContext context;

    @SuppressWarnings("unchecked")
    private ConcurrentKafkaListenerContainerFactory<String, String> factory(String beanName) {
        return context.getBean(beanName, ConcurrentKafkaListenerContainerFactory.class);
    }

    private ConcurrentMessageListenerContainer<String, String> container(String beanName) {
        return factory(beanName).createContainer("payment-events");
    }

    @Test
    @DisplayName("결제 리스너 팩토리의 concurrency 는 4 이고 payment.consumer.auto-startup=false 가 반영된다")
    void 결제리스너팩토리_concurrency는_4다() {
        ConcurrentMessageListenerContainer<String, String> container = container(PAYMENT_FACTORY);

        assertThat(container.getConcurrency()).isEqualTo(4);
        assertThat(container.isAutoStartup()).as("시험 속성 payment.consumer.auto-startup=false").isFalse();
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    @Test
    @DisplayName("결제 컨슈머의 max.poll.records 는 50 이다")
    void 결제컨슈머_max_poll_records는_50이다() {
        Object maxPollRecords = factory(PAYMENT_FACTORY).getConsumerFactory().getConfigurationProperties()
                .get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);

        assertThat(maxPollRecords).isEqualTo(50);
    }

    @Test
    @DisplayName("결제 오류 처리기는 지수 백오프 1초 시작 5회 뒤 DLQ 로 발행한다")
    @SuppressWarnings("unchecked")
    void 결제오류처리기는_지수백오프_1초_시작_5회_뒤_DLQ로_발행한다() {
        Object handler = container(PAYMENT_FACTORY).getCommonErrorHandler();
        assertThat(handler).isInstanceOf(DefaultErrorHandler.class);

        Object tracker = ReflectionTestUtils.getField(handler, "failureTracker");
        ExponentialBackOffWithMaxRetries backOff =
                (ExponentialBackOffWithMaxRetries) ReflectionTestUtils.getField(tracker, "backOff");
        assertThat(backOff.getInitialInterval()).isEqualTo(1000L);
        assertThat(backOff.getMultiplier()).isEqualTo(2.0);
        assertThat(backOff.getMaxRetries()).isEqualTo(5);

        Object recoverer = ReflectionTestUtils.getField(tracker, "recoverer");
        assertThat(recoverer).isInstanceOf(DeadLetterPublishingRecoverer.class);
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination =
                (BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>)
                        ReflectionTestUtils.getField(recoverer, "destinationResolver");
        TopicPartition target = destination.apply(new ConsumerRecord<>("payment-events", 2, 7L, "k", "v"),
                new IllegalStateException("poison"));
        assertThat(target.topic()).isEqualTo("payment-events-dlq");
    }

    @Test
    @DisplayName("payload 파싱 실패는 재시도 없이 곧장 DLQ 로 보내고(재시도 불가 분류) 그 밖의 예외는 재시도한다")
    void 결제오류처리기는_파싱실패를_재시도하지_않고_그_밖의_예외는_재시도한다() {
        Object handler = container(PAYMENT_FACTORY).getCommonErrorHandler();
        // 리스너 예외는 ListenerExecutionFailedException 에 감싸여 오므로 감싼 모양으로 분류를 본다(원인 사슬을 따라 내려간다)
        BinaryExceptionClassifier classifier = (BinaryExceptionClassifier) ReflectionTestUtils.invokeMethod(handler, "getClassifier");

        ListenerExecutionFailedException parseFailure = new ListenerExecutionFailedException("listener failed",
                new PaymentEventParseException("PaymentApproved 레코드를 읽지 못했다", new IllegalStateException("bad json")));
        ListenerExecutionFailedException dbFailure = new ListenerExecutionFailedException("listener failed",
                new IllegalStateException("db down"));

        assertThat(classifier.classify(parseFailure)).as("파싱 실패는 재시도해도 같다").isFalse();
        assertThat(classifier.classify(dbFailure)).as("DB 장애는 재시도 대상").isTrue();
    }

    @Test
    @DisplayName("payment-events 토픽은 파티션 4개이고 DLQ 토픽 payment-events-dlq 가 선언돼 있다")
    void payment_events_토픽은_파티션_4개다() {
        var topics = context.getBeansOfType(NewTopic.class).values();

        assertThat(topics).filteredOn(t -> t.name().equals("payment-events"))
                .singleElement().satisfies(t -> assertThat(t.numPartitions()).isEqualTo(4));
        assertThat(topics).filteredOn(t -> t.name().equals("payment-events-dlq"))
                .singleElement().satisfies(t -> assertThat(t.numPartitions()).isEqualTo(1));
    }

    @Test
    @DisplayName("공용 결제 팩토리(쿠폰 컨슈머가 사용)는 concurrency 1 · max.poll.records 미지정 그대로다")
    void 공용_결제팩토리는_바뀌지_않았다() {
        String shared = "paymentEventKafkaListenerContainerFactory";

        assertThat(container(shared).getConcurrency()).isEqualTo(1);
        assertThat(factory(shared).getConsumerFactory().getConfigurationProperties())
                .doesNotContainKey(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);
        assertThat(container(shared).getCommonErrorHandler()).isNull();
    }
}
