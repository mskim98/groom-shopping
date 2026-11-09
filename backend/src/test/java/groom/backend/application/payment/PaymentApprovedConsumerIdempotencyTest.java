package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.kafka.PaymentEventConsumer;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

// 같은 PaymentApproved 레코드가 다시 와도 Payment 상태(READY 가 아니면 건너뜀)로 걸러지는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=1000",
        "payment.confirm.poll-interval-ms=50",
        "payment.compensation.retry-delay-ms=600000" // 배경 보상 배치가 이 시험의 보상 행을 가져가지 않게 늦춘다
})
@DisplayName("결제 승인 컨슈머 멱등")
class PaymentApprovedConsumerIdempotencyTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 알림이 한 번만 나가는지 확인하는 지점

    @Autowired
    private PaymentEventConsumer consumer;
    @Autowired
    private ObjectMapper objectMapper;
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
        payment = fixture.payment(productId, 1);
        paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("같은 레코드를 두 번 받으면 두 번째는 건너뛰어 재고는 한 번만 줄고 보상 행도 예외도 없다")
    void consume_같은_레코드_두번_재고는_한번만_줄어든다() throws Exception {
        PaymentApprovedEvent event = PaymentFixture.approvedEvent(payment, productId, 1, paymentKey);
        ConsumerRecord<String, String> record = PaymentFixture.record(objectMapper, event);

        consumer.consume(record);
        assertThatCode(() -> consumer.consume(record)).doesNotThrowAnyException();

        assertThat(fixture.stockOf(productId)).as("두 번 받아도 주문 수량 1 만 차감").isEqualTo(9);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(fixture.compensationCountOf(payment.getId())).isZero();
        verify(paymentNotificationService, times(1)).sendStockReducedNotifications(any(), any());
    }

    @Test
    @DisplayName("이미 DONE 인 결제의 레코드가 재전달돼도 재고 · 보상 행 · 예외 어느 것도 일어나지 않는다")
    void consume_이미_DONE인_결제의_재전달은_보상행도_예외도_없다() throws Exception {
        jdbc.update("UPDATE payment SET status = 'DONE' WHERE id = ?", payment.getId());
        PaymentApprovedEvent event = PaymentFixture.approvedEvent(payment, productId, 1, paymentKey);

        assertThatCode(() -> consumer.consume(PaymentFixture.record(objectMapper, event)))
                .doesNotThrowAnyException();

        assertThat(fixture.stockOf(productId)).isEqualTo(10);
        assertThat(fixture.compensationCountOf(payment.getId())).isZero();
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
    }
}
