package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.kafka.PaymentEventConsumer;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
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

// 컨슈머 메서드를 Kafka 없이 직접 불러 PaymentApproved 레코드의 후처리(DONE · CONFIRMED · 재고 · 알림)를 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=1000",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("PaymentApproved 컨슈머 후처리")
class PaymentApprovedConsumerTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 커밋 뒤 호출 여부를 확인하는 지점

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
        payment = fixture.payment(productId, 2);
        paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("READY 결제의 PaymentApproved 레코드를 받으면 DONE · CONFIRMED 로 바뀌고 DB 재고가 주문 수량만큼 줄어든다")
    void consume_READY결제_DONE_CONFIRMED_재고차감까지_끝낸다() throws Exception {
        PaymentApprovedEvent event = PaymentFixture.approvedEvent(payment, productId, 2, paymentKey);

        consumer.consume(PaymentFixture.record(objectMapper, event));

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(fixture.orderStatusOf(payment.getOrderId())).isEqualTo("CONFIRMED");
        assertThat(fixture.stockOf(productId)).as("주문 수량 2 만큼 감소").isEqualTo(8);
    }

    @Test
    @DisplayName("후처리가 커밋되면 그 뒤에 재고 알림과 장바구니 비우기를 부른다")
    void consume_성공하면_커밋뒤에_알림과_장바구니비우기를_부른다() throws Exception {
        PaymentApprovedEvent event = PaymentFixture.approvedEvent(payment, productId, 2, paymentKey);

        consumer.consume(PaymentFixture.record(objectMapper, event));

        verify(paymentNotificationService).sendStockReducedNotifications(any(), eq(1L));
        verify(paymentNotificationService).clearCartItems(eq(1L), eq(payment.getOrderId()), any());
    }

    @Test
    @DisplayName("Toss 응답이 이벤트 JSON 을 거쳐도 승인 반영에 쓰는 필드가 그대로 남는다")
    void 직렬화_Toss응답이_이벤트JSON을_거쳐도_승인필드가_보존된다() throws Exception {
        TossPaymentResponse original = objectMapper.readValue("""
                {"mId":"tosspayments","version":"2022-11-16","paymentKey":"pk-ser","orderId":"ord","orderName":"주문",
                 "method":"카드","status":"DONE","type":"NORMAL","currency":"KRW",
                 "totalAmount":2000,"balanceAmount":2000,"suppliedAmount":1819,"vat":181,
                 "taxFreeAmount":0,"taxExemptionAmount":0,
                 "lastTransactionKey":"tx-last","transactionKey":"tx","requestedAt":"2026-11-09T10:00:00+09:00",
                 "approvedAt":"2026-11-09T10:00:02+09:00","useEscrow":false,"cultureExpense":false,
                 "isPartialCancelable":true,
                 "card":{"issuerCode":"11","number":"1234****","amount":2000},
                 "receipt":{"url":"https://example.test/receipt"},"checkout":{"url":"https://example.test/checkout"}}
                """, TossPaymentResponse.class);
        PaymentApprovedEvent event = new PaymentApprovedEvent(payment.getId(), payment.getOrderId(), 2000, original,
                java.util.List.of(new PaymentApprovedEvent.ReservedItem(productId, 2)));

        PaymentApprovedEvent restored = objectMapper.readValue(
                objectMapper.writeValueAsString(event), PaymentApprovedEvent.class);

        assertThat(restored.tossResponse()).usingRecursiveComparison().isEqualTo(original);
        assertThat(restored.tossResponse().getLastTransactionKey()).isEqualTo("tx-last");
        assertThat(restored.tossResponse().getBalanceAmount()).isEqualTo(2000);
        assertThat(restored.tossResponse().getIsPartialCancelable()).isTrue();
        assertThat(restored.reservedItems()).containsExactly(new PaymentApprovedEvent.ReservedItem(productId, 2));
        assertThat(restored.paymentId()).isEqualTo(payment.getId());
    }
}
