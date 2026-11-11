package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.kafka.PaymentEventConsumer;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

// 결제 승인은 장바구니를 건드리지 않는다 - 승인 뒤에도 주문한 상품의 cart_item 행과 수량이 그대로다(비우기는 따로 부르는 API 몫이다)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        // 배경 보상 배치가 이 시험의 보상 행을 가져가지 않게 늦춘다
        "payment.compensation.retry-delay-ms=600000"
})
@DisplayName("결제 승인 뒤의 장바구니")
class CartKeptOnApprovalTest {

    private static final long BUYER = 1L;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private TossPaymentClient tossPaymentClient; // 외부 Toss 호출 차단

    @Autowired
    private PaymentEventConsumer consumer;
    @Autowired
    private PaymentApplicationService paymentApplicationService;
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
    @Autowired
    @Qualifier("notificationExecutor")
    private Executor notificationExecutor;

    private PaymentFixture fixture;
    private UUID p1;
    private UUID p2;
    private Payment payment;
    private String paymentKey;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        p1 = fixture.product(10);
        p2 = fixture.product(10);
        clearBuyerCart();
        addToBuyerCart(p1, 2);
        addToBuyerCart(p2, 1);
        // 주문은 P1×1 + P2×1 - 장바구니(P1×2 + P2×1)의 일부다
        payment = fixture.payment(p1, 1, p2, 1);
        paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
    }

    @AfterEach
    void tearDown() {
        waitUntilNotificationWorkIsIdle();
        clearBuyerCart();
        jdbc.update("DELETE FROM stock_ledger WHERE payment_id = ?", payment.getId());
        fixture.cleanupPaymentOnly(payment);
        fixture.deleteProduct(p1);
        fixture.deleteProduct(p2);
    }

    @Test
    @DisplayName("컨슈머 경로로 승인되면 결제는 DONE 이고 장바구니는 P1×2 + P2×1 그대로다")
    void 승인_컨슈머경로_장바구니_행과_수량이_그대로다() throws Exception {
        consumer.consume(PaymentFixture.record(objectMapper, approvedEvent()));

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(buyerCartRows()).as("승인 뒤 cart_item").containsExactlyInAnyOrderEntriesOf(Map.of(p1, 2, p2, 1));
    }

    @Test
    @DisplayName("테스트용 확정(confirmPaymentForTest)으로 승인되어도 장바구니는 P1×2 + P2×1 그대로다")
    void 승인_테스트확정경로_장바구니_행과_수량이_그대로다() {
        paymentApplicationService.confirmPaymentForTest(payment.getOrderId());

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(buyerCartRows()).as("승인 뒤 cart_item").containsExactlyInAnyOrderEntriesOf(Map.of(p1, 2, p2, 1));
    }

    private PaymentApprovedEvent approvedEvent() {
        int amount = PaymentFixture.PRICE * 2;
        return new PaymentApprovedEvent(payment.getId(), payment.getOrderId(),
                amount, PaymentFixture.tossResponse(paymentKey, amount), List.of());
    }

    private void waitUntilNotificationWorkIsIdle() {
        var pool = ((ThreadPoolTaskExecutor) notificationExecutor).getThreadPoolExecutor();
        await().atMost(Duration.ofSeconds(15)).until(() -> pool.getTaskCount() == pool.getCompletedTaskCount());
    }

    private void clearBuyerCart() {
        jdbc.update("DELETE FROM cart_item WHERE cart_id IN (SELECT id FROM cart WHERE user_id = ?)", BUYER);
    }

    private void addToBuyerCart(UUID productId, int quantity) {
        jdbc.update("INSERT INTO cart_item (cart_id, product_id, quantity) SELECT id, ?, ? FROM cart WHERE user_id = ?",
                productId, quantity, BUYER);
    }

    private Map<UUID, Integer> buyerCartRows() {
        Map<UUID, Integer> rows = new java.util.HashMap<>();
        jdbc.query("SELECT ci.product_id, ci.quantity FROM cart_item ci JOIN cart c ON c.id = ci.cart_id WHERE c.user_id = ?",
                rs -> {
                    rows.put(rs.getObject("product_id", UUID.class), rs.getInt("quantity"));
                }, BUYER);
        return rows;
    }
}
