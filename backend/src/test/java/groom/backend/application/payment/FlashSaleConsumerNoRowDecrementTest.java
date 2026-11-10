package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.payment.TossPaymentClient;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 컨슈머 후처리(TX2)가 플래시 세일 상품의 재고 행은 건드리지 않고, 플래시가 아닌 상품은 조건부 UPDATE 로 낮추는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=300",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("컨슈머 후처리의 재고 행 쓰기 분기")
class FlashSaleConsumerNoRowDecrementTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;
    @MockBean
    private PaymentNotificationService paymentNotificationService;
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

    private PaymentFixture fixture;
    private UUID productId;
    private Payment payment;
    private String paymentKey;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        fixture.cleanup(payment, productId);
    }

    private void confirmThenConsume() throws Exception {
        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);
        String payload = jdbc.queryForObject(
                "SELECT payload FROM payment_outbox WHERE aggregate_id = ?", String.class, payment.getId());
        paymentApplicationService.applyApprovedEvent(objectMapper.readValue(payload, PaymentApprovedEvent.class));
    }

    @Test
    @DisplayName("플래시 상품은 후처리 뒤 결제 DONE · 주문 CONFIRMED 이고 product.stock 은 호출 전 값이다")
    void applyApprovedEvent_플래시상품_DONE과_CONFIRMED이고_재고행은_호출전값이다() throws Exception {
        productId = fixture.flashProduct(10);
        productStockRedisRepository.initStock(productId, 10);
        payment = fixture.payment(productId, 1);

        confirmThenConsume();

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(fixture.orderStatusOf(payment.getOrderId())).isEqualTo("CONFIRMED");
        assertThat(fixture.stockOf(productId)).as("product.stock").isEqualTo(10);
        assertThat(fixture.unappliedSumOf(productId)).as("원장이 판매를 들고 있다").isEqualTo(1);
    }

    @Test
    @DisplayName("플래시가 아닌 상품은 같은 후처리가 주문 수량만큼 조건부 UPDATE 로 product.stock 을 낮춘다")
    void applyApprovedEvent_일반상품_주문수량만큼_재고행을_낮춘다() throws Exception {
        productId = fixture.product(10);
        payment = fixture.payment(productId, 3);
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE * 3));

        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE * 3);
        String payload = jdbc.queryForObject(
                "SELECT payload FROM payment_outbox WHERE aggregate_id = ?", String.class, payment.getId());
        paymentApplicationService.applyApprovedEvent(objectMapper.readValue(payload, PaymentApprovedEvent.class));

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(fixture.stockOf(productId)).isEqualTo(7);
        assertThat(fixture.ledgerRows(payment.getId())).as("일반 상품은 원장이 없다").isEmpty();
    }
}
