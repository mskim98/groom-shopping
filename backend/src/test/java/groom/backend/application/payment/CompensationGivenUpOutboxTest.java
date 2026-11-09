package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.infrastructure.kafka.PaymentEventPublisher;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentCompensationRepository;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        // 배경 보상 배치(fixedDelay)가 이 시험의 보상 행을 가져가지 않게 늦춘다
        "payment.compensation.retry-delay-ms=600000"})
@DisplayName("GIVEN_UP 전환의 DLQ 이벤트를 Outbox 로 기록(T3-14)")
class CompensationGivenUpOutboxTest {

    private static final String DLQ_TOPIC = "payment-compensation-dlq";

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    // 폴러를 막아 Outbox 행이 PENDING 으로 남아 있는 것을 확인한다
    @MockBean
    private PaymentEventPublisher paymentEventPublisher;
    // 배치가 Kafka 로 직접 보내는 일이 없음을 확인하는 지점
    @MockBean(name = "paymentEventKafkaTemplate")
    private KafkaTemplate<String, String> paymentEventKafkaTemplate;
    @SpyBean
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private PaymentCompensationService compensationService;
    @Autowired
    private SpringDataPaymentCompensationRepository compensationJpa;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private PaymentFixture fixture;
    private UUID productId;
    private Payment payment;
    private UUID compensationId;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(10);
        payment = fixture.payment(productId, 1);
        compensationId = compensationJpa.save(PaymentCompensation.builder()
                .paymentId(payment.getId()).paymentKey("pk-" + UUID.randomUUID()).amount(1000)
                .reason("DB 후처리 실패").maxRetryCount(5).build()).getId();
        // 마지막 재시도(5번째 실패)를 앞둔 PROCESSING 행 - 이번 실패가 GIVEN_UP 이 된다
        jdbc.update("UPDATE payment_compensation SET status = 'PROCESSING', retry_count = 4 WHERE id = ?",
                compensationId);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    private String compensationStatus() {
        return jdbc.queryForObject("SELECT status FROM payment_compensation WHERE id = ?", String.class, compensationId);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbc.queryForList(
                "SELECT topic, event_type, status, payload FROM payment_outbox WHERE aggregate_id = ?", payment.getId());
    }

    @Test
    @DisplayName("GIVEN_UP 으로 전환되면 DLQ 토픽을 가진 Outbox 행이 한 건 생기고 Kafka 로 직접 보내지 않는다")
    void GIVEN_UP전환_DLQ토픽_아웃박스행이_한건_생긴다() {
        PaymentCompensation compensation = compensationJpa.findById(compensationId).orElseThrow();

        compensationService.recordResult(compensation, new RuntimeException("toss down"));

        assertThat(compensationStatus()).isEqualTo("GIVEN_UP");
        assertThat(outboxRows()).hasSize(1).first().satisfies(row -> {
            assertThat(row.get("topic")).isEqualTo(DLQ_TOPIC);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat((String) row.get("payload")).contains(compensation.getPaymentKey());
        });
        verifyNoInteractions(paymentEventKafkaTemplate);
    }

    @Test
    @DisplayName("Outbox 저장이 실패하면 GIVEN_UP 전환도 같은 트랜잭션으로 롤백된다")
    void GIVEN_UP전환_아웃박스저장이_실패하면_상태전환도_롤백된다() {
        doThrow(new IllegalStateException("outbox down")).when(outboxRepository).save(any());
        PaymentCompensation compensation = compensationJpa.findById(compensationId).orElseThrow();

        assertThatThrownBy(() -> compensationService.recordResult(compensation, new RuntimeException("toss down")))
                .hasMessageContaining("outbox down");

        assertThat(compensationStatus()).as("롤백 뒤 상태 - GIVEN_UP 만 남고 DLQ 이벤트가 없으면 안 된다")
                .isEqualTo("PROCESSING");
        assertThat(outboxRows()).isEmpty();
    }

    private void reconcileOverExistingRow(String existingStatus) {
        jdbc.update("UPDATE payment_compensation SET status = ? WHERE id = ?", existingStatus, compensationId);
        String key = jdbc.queryForObject("SELECT payment_key FROM payment_compensation WHERE id = ?",
                String.class, compensationId);

        compensationService.recordReconcileNeeded(payment.getId(), key, 1000,
                "[RECONCILE_MANUAL_CANCEL] Toss 취소 성공, DB 갱신 실패");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_compensation WHERE payment_id = ?",
                Long.class, payment.getId())).as("보상 행은 결제당 하나").isEqualTo(1L);
        assertThat(jdbc.queryForMap("SELECT status, last_error FROM payment_compensation WHERE id = ?", compensationId))
                .containsEntry("status", "RECONCILE_NEEDED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM payment_compensation WHERE id = ?",
                String.class, compensationId)).contains("RECONCILE_MANUAL_CANCEL");
        assertThat(outboxRows()).as("대사 필요 DLQ 이벤트").hasSize(1).first()
                .satisfies(row -> assertThat(row.get("topic")).isEqualTo(DLQ_TOPIC));
    }

    @Test
    @DisplayName("결제에 이미 SUPERSEDED 보상 행이 있어도 대사 필요 건은 행을 갱신하고 DLQ 이벤트를 남긴다")
    void 대사필요_기존_SUPERSEDED행_행갱신과_DLQ이벤트를_남긴다() {
        reconcileOverExistingRow("SUPERSEDED");
    }

    @Test
    @DisplayName("결제에 이미 NOT_APPROVED 보상 행이 있어도 대사 필요 건은 행을 갱신하고 DLQ 이벤트를 남긴다")
    void 대사필요_기존_NOT_APPROVED행_행갱신과_DLQ이벤트를_남긴다() {
        reconcileOverExistingRow("NOT_APPROVED");
    }

    @Test
    @DisplayName("결제에 아직 처리 중(PROCESSING)인 보상 행이 있어도 대사 필요 건은 행을 갱신하고 DLQ 이벤트를 남긴다")
    void 대사필요_기존_PROCESSING행_행갱신과_DLQ이벤트를_남긴다() {
        reconcileOverExistingRow("PROCESSING");
    }

    @Test
    @DisplayName("결제에 재시도 대기(PENDING)인 보상 행이 있어도 대사 필요 건은 행을 갱신하고 DLQ 이벤트를 남긴다")
    void 대사필요_기존_PENDING행_행갱신과_DLQ이벤트를_남긴다() {
        reconcileOverExistingRow("PENDING");
    }
}
