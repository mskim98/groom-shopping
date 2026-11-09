package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.infrastructure.payment.TossRetryLaterException;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentCompensationRepository;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
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
@DisplayName("보상 유예 상태 복귀(T3-4)")
class CompensationDeferTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private PaymentCompensationService compensationService;
    @Autowired
    private PaymentCompensationRepository compensationRepository;
    @Autowired
    private SpringDataPaymentCompensationRepository compensationJpa;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private MeterRegistry meterRegistry;
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
        // 배치가 선점한 상태(PROCESSING)에서 결과를 기록하는 상황을 만든다
        jdbc.update("UPDATE payment_compensation SET status = 'PROCESSING' WHERE id = ?", compensationId);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    private PaymentCompensation reload() {
        return compensationJpa.findById(compensationId).orElseThrow();
    }

    @Test
    @DisplayName("429 유예 뒤 행은 PROCESSING 이 아니고 45초(±20%) 뒤의 nextRetryAt 을 가지며 재시도 횟수는 그대로다")
    void 유예_429_PROCESSING이_아니고_nextRetryAt이_있다() {
        LocalDateTime before = LocalDateTime.now();
        compensationService.recordResult(reload(), new TossRetryLaterException("429"));

        PaymentCompensation after = reload();
        assertThat(after.getStatus().name()).as("유예 뒤 상태").isIn("PENDING", "FAILED");
        assertThat(after.getRetryCount()).as("유예는 실패로 세지 않는다").isZero();
        // 기준 45초에 ±20% 지터 → 36~54초
        assertThat(after.getNextRetryAt())
                .isAfterOrEqualTo(before.plusSeconds(36).minusSeconds(1))
                .isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(54).plusSeconds(1));
    }

    @Test
    @DisplayName("생성 60분이 지난 행이 유예를 받으면 GIVEN_UP 으로 끝나고 DB 카운트 게이지에 반영된다")
    void 유예_생성60분초과_GIVEN_UP으로_끝난다() {
        jdbc.update("UPDATE payment_compensation SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minus(Duration.ofMinutes(61))), compensationId);

        compensationService.recordResult(reload(), new TossRetryLaterException("429"));

        assertThat(reload().getStatus().name()).isEqualTo("GIVEN_UP");
        Long givenUpInDb = jdbc.queryForObject(
                "SELECT count(*) FROM payment_compensation WHERE status = 'GIVEN_UP'", Long.class);
        assertThat(meterRegistry.get("payment_compensation_given_up_total").gauge().value())
                .as("게이지는 메모리 누적이 아니라 DB 의 GIVEN_UP 행 수를 읽는다")
                .isEqualTo(givenUpInDb.doubleValue());
    }

    @Test
    @DisplayName("유예된 행은 nextRetryAt 이 오기 전에는 선점되지 않고 지난 뒤에는 선점된다")
    void 유예_nextRetryAt이전_회수되지_않는다() {
        compensationService.recordResult(reload(), new TossRetryLaterException("429"));
        LocalDateTime nextRetryAt = reload().getNextRetryAt();
        LocalDateTime staleThreshold = LocalDateTime.now().minusMinutes(5);

        // 유예 직후 시각으로 선점해도 이 행은 나오지 않는다(PROCESSING 으로 남아 90초 뒤 회수되던 결함)
        assertThat(compensationRepository.findRetryable(LocalDateTime.now(), staleThreshold, 1000))
                .extracting(PaymentCompensation::getId).doesNotContain(compensationId);

        assertThat(compensationRepository.findRetryable(nextRetryAt.plusSeconds(1), staleThreshold, 1000))
                .extracting(PaymentCompensation::getId).contains(compensationId);
    }
}
