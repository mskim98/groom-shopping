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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

// 이 클래스의 리스너와 폴러가 클래스 종료 뒤에도 살아 다른 클래스의 outbox 행을 먹지 않게 한다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=true",
        "payment.outbox.poll-delay-ms=200",
        "payment.confirm.wait-timeout-ms=10000",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("결제 승인 실제 Kafka 경로(폴러 → 토픽 → 전용 리스너 → 컨슈머)")
class PaymentApprovedKafkaPathTest {

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
    private JdbcTemplate jdbc;

    private PaymentFixture fixture;
    private UUID productId;
    private Payment payment;
    private String paymentKey;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(10);
        payment = fixture.payment(productId, 2);
        paymentKey = "pk-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("confirm 후 폴러·토픽·리스너·컨슈머를 거쳐 결제 DONE, 주문 CONFIRMED, 재고 차감, outbox PUBLISHED 가 된다")
    void confirm_폴러_토픽_리스너_컨슈머를_거쳐_DONE_CONFIRMED_재고차감_outbox_PUBLISHED로_끝난다() {
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE * 2));

        // 컨슈머를 직접 부르지 않는다 - 확정 호출이 폴러 → payment-events → 결제 전용 리스너 → 컨슈머를 실제로 거쳐 DONE 이 돼야 한다
        Payment result = paymentApplicationService.confirmPayment(
                paymentKey, payment.getOrderId(), PaymentFixture.PRICE * 2);

        assertThat(result.getStatus()).as("대기 상한(10초) 안에 후처리가 끝나 DONE 을 돌려받는다").isEqualTo(PaymentStatus.DONE);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(fixture.orderStatusOf(payment.getOrderId())).isEqualTo("CONFIRMED");
        assertThat(fixture.stockOf(productId)).as("주문 수량 2 만큼 감소").isEqualTo(8);
        // 폴러는 send().get() 이 돌아온 뒤에야 PUBLISHED 로 표시하는데 컨슈머가 먼저 끝날 수 있다 - 한 번 읽지 않고 기다린다
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM payment_outbox WHERE aggregate_id = ?",
                        String.class, payment.getId())).isEqualTo("PUBLISHED"));
    }
}
