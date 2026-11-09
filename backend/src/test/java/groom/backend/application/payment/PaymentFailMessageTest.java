package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
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
@TestPropertySource(properties = "spring.kafka.streams.auto-startup=false")
@DisplayName("실패 사유 컬럼 길이 초과 방어(T3-2)")
class PaymentFailMessageTest {

    private static final String LONG_REASON = "x".repeat(1000);

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private PaymentCompensationService compensationService;
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

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(10);
        payment = fixture.payment(productId, 1);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("천 자 길이의 실패 사유로 결제를 실패 처리해도 예외 없이 FAILED 로 기록된다")
    void markPaymentFailed_천자사유_예외없이_FAILED로_기록된다() {
        assertThatCode(() -> paymentApplicationService.markPaymentFailed(
                payment.getId(), "DB_POST_PROCESS_FAILED", LONG_REASON)).doesNotThrowAnyException();

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
        String stored = jdbc.queryForObject(
                "SELECT failure_message FROM payment WHERE id = ?", String.class, payment.getId());
        assertThat(stored).hasSizeLessThanOrEqualTo(255).startsWith("xxx");
    }

    @Test
    @DisplayName("천 자 길이의 보상 사유를 기록해도 예외 없이 저장된다")
    void recordCompensationIntent_천자사유_예외없이_저장된다() {
        assertThatCode(() -> compensationService.recordCompensationIntent(
                payment.getId(), "pk-" + UUID.randomUUID(), 1000, LONG_REASON)).doesNotThrowAnyException();

        String stored = jdbc.queryForObject(
                "SELECT reason FROM payment_compensation WHERE payment_id = ?", String.class, payment.getId());
        assertThat(stored).hasSizeLessThanOrEqualTo(500).startsWith("xxx");
    }
}
