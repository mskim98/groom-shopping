package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.kafka.PaymentEventConsumer;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

// 재고가 임계값 위에서 아래(이하)로 넘어가는 결제만 장바구니 사용자에게 알린다 - 이미 아래인 재고를 또 낮추는 결제는 알리지 않는다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("재고 임계값 통과 알림")
class StockThresholdCrossingNotificationTest {

    private static final long BUYER = 1L;
    private static final List<Long> OTHER_USERS = List.of(2L, 3L);

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

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
    @Autowired
    @Qualifier("notificationExecutor")
    private Executor notificationExecutor;

    private PaymentFixture fixture;
    private UUID productId;
    private final List<Payment> payments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
    }

    @AfterEach
    void tearDown() {
        // 준비가 중간에 실패했을 수 있다 - 만들어진 것만 지워 첫 실패가 가려지지 않게 한다
        if (productId == null) {
            return;
        }
        jdbc.update("DELETE FROM notifications WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM cart_item WHERE product_id = ?", productId);
        for (Payment payment : payments) {
            fixture.cleanupPaymentOnly(payment);
        }
        fixture.deleteProduct(productId);
    }

    @Test
    @DisplayName("임계값 10 재고 12 에서 3개 결제(12→9)는 구매자를 뺀 장바구니 사용자마다 알림 한 건을 만들고 다음 1개 결제(9→8)는 더하지 않는다")
    void 결제_임계값을_넘는_결제만_알리고_이미_아래인_재고의_결제는_알리지_않는다() throws Exception {
        seedProduct(12);

        pay(3);
        assertThat(awaitNotificationRows(BUYER)).isZero();
        for (long userId : OTHER_USERS) {
            assertThat(rowsOf(userId)).as("user %d 의 알림 행".formatted(userId)).isEqualTo(1);
        }

        pay(1);
        waitUntilNotificationWorkIsIdle();
        assertThat(fixture.stockOf(productId)).isEqualTo(8);
        for (long userId : OTHER_USERS) {
            assertThat(rowsOf(userId)).as("user %d 의 알림 행".formatted(userId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("재고 20 에서 2개 결제(20→18)는 임계값 위에 머물러 알림 행을 더하지 않는다")
    void 결제_임계값_위에_머무는_결제는_알리지_않는다() throws Exception {
        seedProduct(20);

        pay(2);
        waitUntilNotificationWorkIsIdle();

        assertThat(fixture.stockOf(productId)).isEqualTo(18);
        assertThat(totalRows()).isZero();
    }

    @Test
    @DisplayName("재고 11 에서 1개 결제(11→10)는 임계값에 닿는 순간이라 알리고 임계값이 비어 있으면 10 으로 본다")
    void 결제_임계값이_null_이면_10으로_보고_재고가_10에_닿는_결제를_알린다() throws Exception {
        seedProduct(11);
        jdbc.update("UPDATE product SET threshold_value = NULL WHERE id = ?", productId);

        pay(1);
        awaitNotificationRows(OTHER_USERS.get(0));

        for (long userId : OTHER_USERS) {
            assertThat(rowsOf(userId)).as("user %d 의 알림 행".formatted(userId)).isEqualTo(1);
        }
    }

    private void seedProduct(int stock) {
        productId = fixture.product(stock);
        jdbc.update("UPDATE product SET threshold_value = 10 WHERE id = ?", productId);
        for (long userId : List.of(BUYER, OTHER_USERS.get(0), OTHER_USERS.get(1))) {
            jdbc.update("INSERT INTO cart_item (cart_id, product_id, quantity) SELECT id, ?, 1 FROM cart WHERE user_id = ?",
                    productId, userId);
        }
    }

    // 결제 한 건을 컨슈머로 승인 처리한다 (커밋 뒤 알림 작업이 notificationExecutor 에 제출된 채로 돌아온다)
    private void pay(int quantity) throws Exception {
        Payment payment = fixture.payment(productId, quantity);
        payments.add(payment);
        String paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
        consumer.consume(PaymentFixture.record(objectMapper,
                PaymentFixture.approvedEvent(payment, productId, quantity, paymentKey)));
    }

    // 제출된 작업이 전부 끝난 상태 - 알림이 "없다"는 검사는 고정 대기가 아니라 이 상태를 기다린 뒤에 한다
    private void waitUntilNotificationWorkIsIdle() {
        var pool = ((ThreadPoolTaskExecutor) notificationExecutor).getThreadPoolExecutor();
        await().atMost(Duration.ofSeconds(15)).until(() -> pool.getTaskCount() == pool.getCompletedTaskCount());
    }

    private int awaitNotificationRows(long userId) {
        waitUntilNotificationWorkIsIdle();
        return rowsOf(userId);
    }

    private int rowsOf(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE product_id = ? AND user_id = ?",
                Integer.class, productId, userId);
    }

    private int totalRows() {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE product_id = ?",
                Integer.class, productId);
    }
}
