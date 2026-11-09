package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.infrastructure.payment.TossPaymentNotFoundException;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentCompensationRepository;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        // 배경 보상 배치(fixedDelay)가 이 시험의 보상 행을 가져가지 않게 늦춘다
        "payment.compensation.retry-delay-ms=600000"})
@DisplayName("승인된 적 없는 결제의 보상 종료(T3-5 한계 보완)")
class CompensationNotApprovedTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private PaymentCompensationService compensationService;
    @Autowired
    private SpringDataPaymentCompensationRepository compensationJpa;
    @Autowired
    private PaymentCompensationRepository compensationRepository;
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
                .reason("Toss 승인 응답 시간 초과").maxRetryCount(5).build()).getId();
        jdbc.update("UPDATE payment_compensation SET status = 'PROCESSING' WHERE id = ?", compensationId);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("Toss 가 결제 없음(404)으로 답하면 NOT_APPROVED 로 끝나고 재시도·DLQ 이벤트가 없다")
    void 결제없음_NOT_APPROVED로_끝나고_재시도와_DLQ가_없다() {
        PaymentCompensation compensation = compensationJpa.findById(compensationId).orElseThrow();

        compensationService.recordResult(compensation, new TossPaymentNotFoundException("결제 없음"));

        assertThat(jdbc.queryForMap("SELECT status, retry_count FROM payment_compensation WHERE id = ?",
                compensationId)).containsEntry("status", "NOT_APPROVED").containsEntry("retry_count", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE aggregate_id = ?",
                Long.class, payment.getId())).as("DLQ Outbox 행").isZero();
    }

    private void compensateOverTerminalRow(String terminalStatus) {
        jdbc.update("UPDATE payment_compensation SET status = ?, retry_count = 3, next_retry_at = NULL, last_error = '이전 사유' WHERE id = ?",
                terminalStatus, compensationId);
        String newKey = "pk-" + UUID.randomUUID();

        compensationService.compensate(payment.getId(), newKey, 1000, "재시도 확정이 승인됐으나 후처리 실패");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_compensation WHERE payment_id = ?",
                Long.class, payment.getId())).as("결제당 보상 행은 하나").isEqualTo(1L);
        assertThat(jdbc.queryForMap(
                "SELECT status, reason, payment_key, retry_count, last_error FROM payment_compensation WHERE id = ?",
                compensationId))
                .containsEntry("status", "PENDING")
                .containsEntry("reason", "재시도 확정이 승인됐으나 후처리 실패")
                .containsEntry("payment_key", newKey)
                .containsEntry("retry_count", 0)
                .containsEntry("last_error", null);
        // 배치의 선점 조회가 이 행을 집어 간다
        assertThat(compensationRepository.findRetryable(LocalDateTime.now().plusSeconds(1),
                LocalDateTime.now().minusMinutes(5), 1000))
                .extracting(PaymentCompensation::getId).contains(compensationId);
    }

    @Test
    @DisplayName("이미 NOT_APPROVED 로 끝난 결제의 재시도 확정이 승인된 뒤 후처리가 실패하면 그 행을 다시 열어 환불을 시도한다")
    void compensate_기존_NOT_APPROVED행_다시_PENDING으로_열린다() {
        compensateOverTerminalRow("NOT_APPROVED");
    }

    @Test
    @DisplayName("이미 SUPERSEDED 로 끝난 결제에 새 보상 의도가 오면 그 행을 다시 열어 환불을 시도한다")
    void compensate_기존_SUPERSEDED행_다시_PENDING으로_열린다() {
        compensateOverTerminalRow("SUPERSEDED");
    }
}
