package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

// 확정 한 번의 구간별 시간이 타이머 payment.confirm.stage (태그 stage)로 남는지 본다 - 부하 측정이 /actuator/prometheus 에서 읽는다
// 실제 Kafka 경로(폴러 → 토픽 → 리스너 → 컨슈머)를 거쳐야 여섯 구간이 모두 채워진다
// 이 클래스의 리스너와 폴러가 클래스 종료 뒤에도 살아 다른 클래스의 outbox 행을 먹지 않게 한다
@SpringBootTest
@AutoConfigureObservability
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=true",
        "payment.outbox.poll-delay-ms=200",
        "payment.confirm.wait-timeout-ms=10000",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("결제 확정 구간 타이머")
class ConfirmStageTimerTest {

    private static final String TIMER = "payment.confirm.stage";
    private static final List<String> STAGES = List.of(
            "toss_call",                  // Toss 승인 호출
            "ready_outbox_commit",        // READY 전이 + outbox 적재 커밋
            "outbox_wait_claim",          // outbox 행이 폴러가 집어 가기까지 기다린 시간
            "kafka_publish_ack",          // Kafka 발행 확인
            "consumer_receive_to_commit", // 컨슈머가 받은 뒤 승인 커밋까지
            "commit_to_confirm_seen",     // 승인 커밋 뒤 기다리던 확정이 그것을 보기까지
            "confirm_entry_to_toss",      // confirmPayment 진입 → Toss 호출 직전
            "topic_wait",                 // Kafka 레코드 발행 시각 → 리스너 진입
            "confirm_total");             // confirmPayment 진입 → 끝(컨슈머 대기 포함)

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 비동기 부수효과 차단
    @MockBean
    private TossPaymentClient tossPaymentClient; // 외부 Toss 승인 호출 차단

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private PrometheusMeterRegistry prometheus;
    @Autowired
    private JdbcTemplate jdbc;

    private PaymentFixture fixture;
    private UUID productId;
    private Payment payment;
    private String paymentKey;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(10);
        payment = fixture.payment(productId, 1);
        paymentKey = "pk-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    private long countOf(String stage) {
        Timer timer = registry.find(TIMER).tag("stage", stage).timer();
        return timer == null ? 0 : timer.count();
    }

    private double sumMsOf(String stage) {
        Timer timer = registry.find(TIMER).tag("stage", stage).timer();
        return timer == null ? 0 : timer.totalTime(TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("컨슈머 경로로 확정 한 번이 끝나면 아홉 구간 모두 타이머 count 가 늘고 prometheus 에 나온다")
    void 구간타이머_확정한번_아홉구간_모두_count가_늘고_prometheus에_나온다() {
        long[] before = STAGES.stream().mapToLong(this::countOf).toArray();
        double entryBefore = sumMsOf("confirm_entry_to_toss");
        double tossBefore = sumMsOf("toss_call");
        double readyBefore = sumMsOf("ready_outbox_commit");
        double totalBefore = sumMsOf("confirm_total");
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));

        Payment result = ConfirmAsOwner.confirm(paymentApplicationService, payment, paymentKey, PaymentFixture.PRICE);

        assertThat(result.getStatus()).as("컨슈머 경로로 DONE").isEqualTo(PaymentStatus.DONE);
        // 폴러는 발행을 확인한 뒤에 구간을 기록하는데 컨슈머가 먼저 끝날 수 있다 - 한 번 읽지 않고 기다린다
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            for (int i = 0; i < STAGES.size(); i++) {
                assertThat(countOf(STAGES.get(i))).as("구간 " + STAGES.get(i)).isGreaterThan(before[i]);
            }
        });
        // confirm_total 은 컨슈머 대기 뒤에 기록되므로 앞 세 구간의 합 이상이다(대기 전에 기록하면 이 합보다 작다)
        double frontSum = (sumMsOf("confirm_entry_to_toss") - entryBefore)
                + (sumMsOf("toss_call") - tossBefore) + (sumMsOf("ready_outbox_commit") - readyBefore);
        assertThat(sumMsOf("confirm_total") - totalBefore).as("confirm_total 합(ms)").isGreaterThanOrEqualTo(frontSum);
        String scrape = prometheus.scrape();
        for (String stage : STAGES) {
            assertThat(scrape.lines()
                    .anyMatch(line -> line.startsWith("payment_confirm_stage_seconds_count{")
                            && line.contains("stage=\"" + stage + "\"")))
                    .as("prometheus 에 구간 " + stage).isTrue();
        }
    }
}
