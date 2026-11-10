package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.cart.CartApplicationService;
import groom.backend.application.order.OrderApplicationService;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.domain.order.model.Order;
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
import java.util.concurrent.CountDownLatch;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 결제를 DONE 으로 바꾸는 승인 트랜잭션이 주문한 수량을 장바구니에서 같은 커밋으로 뺀다 - 롤백되면 장바구니도 그대로다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        // 배경 보상 배치가 이 시험의 보상 행을 가져가지 않게 늦춘다
        "payment.compensation.retry-delay-ms=600000"
})
@DisplayName("승인 트랜잭션 안의 장바구니 비우기")
class CartClearedOnApprovalTest {

    private static final long BUYER = 1L;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private TossPaymentClient tossPaymentClient; // 롤백 시험의 보상 기록이 외부 호출을 하지 않게 막는다
    @SpyBean
    private CartApplicationService cartApplicationService; // 비우기 뒤 커밋을 실패시키는 지점

    @Autowired
    private PaymentEventConsumer consumer;
    @Autowired
    private OrderApplicationService orderApplicationService;
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
        clearBuyerCart();
        jdbc.update("DELETE FROM stock_ledger WHERE payment_id = ?", payment.getId());
        fixture.cleanupPaymentOnly(payment);
        fixture.deleteProduct(p1);
        fixture.deleteProduct(p2);
    }

    @Test
    @DisplayName("승인 호출이 돌아온 직후 비동기 작업이 하나도 돌기 전에 장바구니에는 주문하지 않은 P1×1 만 남고 그 장바구니로 만든 주문도 P1×1 이다")
    void 승인_DONE_직후_장바구니에는_주문하지_않은_수량만_남는다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        holdNotificationExecutor(release);
        UUID createdOrderId = null;
        try {
            consumer.consume(PaymentFixture.record(objectMapper, approvedEvent()));

            assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
            assertThat(buyerCartRows()).as("승인 직후 cart_item").containsExactly(Map.entry(p1, 1));
            var view = cartApplicationService.getCartItems(BUYER);
            assertThat(view.getItems()).as("장바구니 조회").singleElement().satisfies(item -> {
                assertThat(item.getProductId()).isEqualTo(p1);
                assertThat(item.getQuantity()).isEqualTo(1);
            });

            Order next = orderApplicationService.createOrder(BUYER, null);
            createdOrderId = next.getId();
            assertThat(next.getOrderItems()).as("다음 주문 항목").singleElement().satisfies(item -> {
                assertThat(item.getProductId()).isEqualTo(p1);
                assertThat(item.getQuantity()).isEqualTo(1);
            });
        } finally {
            release.countDown();
            waitUntilNotificationWorkIsIdle();
            if (createdOrderId != null) {
                jdbc.update("DELETE FROM payment WHERE order_id = ?", createdOrderId);
                orderRepository.deleteById(createdOrderId);
            }
        }
    }

    @Test
    @DisplayName("비우기 단계가 돈 뒤 같은 트랜잭션이 커밋에서 실패하면 장바구니는 P1×2 + P2×1 그대로이고 결제는 DONE 이 아니다")
    void 승인_롤백_장바구니는_그대로이고_결제는_DONE_이_아니다() throws Exception {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            // 비우기 호출은 성공으로 끝났다 - 그 뒤 커밋 직전에 실패시켜 같은 트랜잭션의 쓰기가 전부 되돌아와야 한다
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("commit failed");
                }
            });
            return result;
        }).when(cartApplicationService).clearOrderedItems(any(), any());

        assertThatCode(() -> consumer.consume(PaymentFixture.record(objectMapper, approvedEvent())))
                .doesNotThrowAnyException();

        verify(cartApplicationService).clearOrderedItems(any(), any());
        assertThat(buyerCartRows()).as("롤백 뒤 cart_item").containsExactlyInAnyOrderEntriesOf(Map.of(p1, 2, p2, 1));
        assertThat(fixture.statusOf(payment.getId())).as("롤백 뒤 결제 상태").isNotEqualTo("DONE");
        assertThat(fixture.stockOf(p1)).as("롤백 뒤 재고").isEqualTo(10);
    }

    private PaymentApprovedEvent approvedEvent() {
        int amount = PaymentFixture.PRICE * 2;
        return new PaymentApprovedEvent(payment.getId(), payment.getOrderId(),
                amount, PaymentFixture.tossResponse(paymentKey, amount), List.of());
    }

    // 풀의 기본 스레드 수만큼 멈춘 작업을 넣어 이후 제출되는 비동기 작업이 큐에서 기다리게 한다
    private void holdNotificationExecutor(CountDownLatch release) {
        int core = ((ThreadPoolTaskExecutor) notificationExecutor).getCorePoolSize();
        for (int i = 0; i < core; i++) {
            notificationExecutor.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
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
