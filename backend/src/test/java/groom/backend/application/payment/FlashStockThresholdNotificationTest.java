package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.payment.TossPaymentClient;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

// 플래시 세일 상품도 일반 상품처럼 재고가 임계값 위에서 이하로 넘어가는 승인에서만 장바구니 사용자에게 알린다
// 알림에 쓰는 재고는 예약이 남긴 값(REDIS: Redis 선점 결과, DB: 조건부 UPDATE 결과)이고 알림은 승인 커밋 뒤에 나간다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=300",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("플래시 세일 재고 임계값 알림")
class FlashStockThresholdNotificationTest {

    private static final long BUYER = 1L;
    private static final List<Long> OTHER_USERS = List.of(2L, 3L);

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private TossPaymentClient tossPaymentClient;

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private ProductStockRedisRepository productStockRedisRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
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
        waitUntilNotificationWorkIsIdle();
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        jdbc.update("DELETE FROM notifications WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM cart_item WHERE product_id = ?", productId);
        for (Payment payment : payments) {
            fixture.cleanupPaymentOnly(payment);
        }
        jdbc.update("DELETE FROM stock_ledger WHERE product_id = ?", productId);
        fixture.deleteProduct(productId);
    }

    @Test
    @DisplayName("REDIS 모드 임계값 10 재고 12 에서 3개 승인(12→9)은 구매자를 뺀 장바구니 사용자마다 알림 한 건을 만들고 다음 1개 승인(9→8)은 더하지 않는다")
    void 승인_REDIS모드_임계값을_넘는_승인만_알린다() throws Exception {
        seedFlashProduct(12);
        productStockRedisRepository.initStock(productId, 12);

        confirmAndApply(3);
        waitUntilNotificationWorkIsIdle();
        assertThat(rowsOf(BUYER)).as("구매자의 알림 행").isZero();
        for (long userId : OTHER_USERS) {
            assertThat(rowsOf(userId)).as("user %d 의 알림 행".formatted(userId)).isEqualTo(1);
        }

        confirmAndApply(1);
        waitUntilNotificationWorkIsIdle();
        assertThat(redisStock()).as("Redis 재고").isEqualTo(8);
        for (long userId : OTHER_USERS) {
            assertThat(rowsOf(userId)).as("user %d 의 알림 행".formatted(userId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("임계값을 넘긴 예약이어도 Toss 승인이 실패해 예약이 풀리면 알림 행을 만들지 않는다")
    void 승인실패_예약이_풀리면_알리지_않는다() {
        seedFlashProduct(12);
        productStockRedisRepository.initStock(productId, 12);
        Payment payment = newPayment(3);
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_PG_REJECTED));

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(
                "pk-" + UUID.randomUUID(), payment.getOrderId(), PaymentFixture.PRICE * 3))
                .isInstanceOf(BusinessException.class);
        waitUntilNotificationWorkIsIdle();

        assertThat(totalRows()).isZero();
        assertThat(redisStock()).as("풀린 예약").isEqualTo(12);
    }

    @Test
    @DisplayName("DB 모드(Redis 장애 대체) 임계값 10 재고 12 에서 3개 승인(12→9)도 구매자를 뺀 장바구니 사용자마다 알림 한 건을 만든다")
    void 승인_DB모드_임계값을_넘는_승인을_알린다() throws Exception {
        seedFlashProduct(12);
        jdbc.update("UPDATE product SET stock_mode = 'DB', stock_mode_changed_at = now() WHERE id = ?", productId);

        confirmAndApply(3);
        waitUntilNotificationWorkIsIdle();

        assertThat(fixture.stockOf(productId)).as("DB 모드 예약이 낮춘 product.stock").isEqualTo(9);
        assertThat(rowsOf(BUYER)).as("구매자의 알림 행").isZero();
        for (long userId : OTHER_USERS) {
            assertThat(rowsOf(userId)).as("user %d 의 알림 행".formatted(userId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("예약이 남긴 재고 값이 없는 승인 이벤트(옛 outbox payload)는 결제가 DONE 이 되고 알림 행을 만들지 않는다")
    void 승인_예약_재고_값이_없는_옛_이벤트는_DONE_이고_알리지_않는다() {
        seedFlashProduct(12);
        // 수량 11 - 값이 없는 항목을 재고 0 으로 읽는 잘못된 처리는 12→1 통과로 보고 알린다
        Payment payment = newPayment(11);
        String paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
        // 승인 전 단계(TX1)가 남겼을 판매 원장 행 - 있어야 후처리가 상품 행을 쓰지 않는 플래시 항목으로 가린다
        jdbc.update("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) VALUES (?, ?, 11, 'SALE', false)",
                payment.getId(), productId);
        int amount = PaymentFixture.PRICE * 11;
        PaymentApprovedEvent oldShape = new PaymentApprovedEvent(payment.getId(), payment.getOrderId(), amount,
                PaymentFixture.tossResponse(paymentKey, amount), List.of(new PaymentApprovedEvent.ReservedItem(productId, 11)));

        paymentApplicationService.applyApprovedEvent(oldShape);
        waitUntilNotificationWorkIsIdle();

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(totalRows()).as("알림 행").isZero();
    }

    private void seedFlashProduct(int stock) {
        productId = fixture.flashProduct(stock);
        jdbc.update("UPDATE product SET threshold_value = 10 WHERE id = ?", productId);
        for (long userId : List.of(BUYER, OTHER_USERS.get(0), OTHER_USERS.get(1))) {
            jdbc.update("INSERT INTO cart_item (cart_id, product_id, quantity) SELECT id, ?, 1 FROM cart WHERE user_id = ?",
                    productId, userId);
        }
    }

    private Payment newPayment(int quantity) {
        Payment payment = fixture.payment(productId, quantity);
        payments.add(payment);
        return payment;
    }

    // 예약 → Toss 승인 → outbox 적재까지 요청 경로로 돌리고, 컨슈머가 받는 후처리를 직접 적용한다
    private void confirmAndApply(int quantity) throws Exception {
        Payment payment = newPayment(quantity);
        String paymentKey = "pk-" + UUID.randomUUID();
        int amount = PaymentFixture.PRICE * quantity;
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, amount));

        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), amount);
        String payload = jdbc.queryForObject(
                "SELECT payload FROM payment_outbox WHERE aggregate_id = ?", String.class, payment.getId());
        paymentApplicationService.applyApprovedEvent(objectMapper.readValue(payload, PaymentApprovedEvent.class));
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
    }

    // 제출된 작업이 전부 끝난 상태 - 알림이 "없다"는 검사는 고정 대기가 아니라 이 상태를 기다린 뒤에 한다
    private void waitUntilNotificationWorkIsIdle() {
        var pool = ((ThreadPoolTaskExecutor) notificationExecutor).getThreadPoolExecutor();
        await().atMost(Duration.ofSeconds(15)).until(() -> pool.getTaskCount() == pool.getCompletedTaskCount());
    }

    private int redisStock() {
        return Integer.parseInt(redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId));
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
