package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.Map;
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

// 컨슈머를 끈 상태에서 confirm 이 Outbox 에 이벤트를 적재하고 대기 상한 뒤 응답하는 경로를 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=1000",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("결제 승인 Outbox 적재와 확인 대기")
class PaymentConfirmOutboxTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 비동기 부수효과 차단
    @MockBean
    private TossPaymentClient tossPaymentClient; // 외부 Toss 승인 호출 차단

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
        productId = fixture.product(10);
        payment = fixture.payment(productId, 1);
        paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("컨슈머가 멈춘 상태에서 confirm 을 호출하면 대기 상한 뒤 READY 결제를 돌려준다")
    void confirm_컨슈머정지_대기상한뒤_READY_Payment를_돌려준다() {
        Payment result = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(result.getPaymentKeyValue()).isEqualTo(paymentKey);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("READY");
    }

    @Test
    @DisplayName("컨슈머가 멈춘 상태에서 confirm 후 주문은 PENDING 이고 DB 재고는 호출 전 값이다")
    void confirm_컨슈머정지_주문은_PENDING이고_DB재고는_호출전값이다() {
        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        assertThat(fixture.orderStatusOf(payment.getOrderId())).isEqualTo("PENDING");
        assertThat(fixture.stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("컨슈머가 멈춘 상태에서 confirm 하면 PaymentApproved outbox 가 PENDING 으로 1건만 적재된다")
    void confirm_컨슈머정지_PaymentApproved_outbox_PENDING_1건만_적재된다() {
        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_type, status FROM payment_outbox WHERE aggregate_id = ?", payment.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("event_type", "PaymentApproved").containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("이미 READY 인 주문을 다시 확정해도 outbox 는 결제당 1건이고 결제는 READY 그대로다")
    void confirm_READY_주문을_다시_확정해도_outbox는_1건이다() {
        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        Payment second = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        assertThat(second.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE aggregate_id = ?",
                Integer.class, payment.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("confirm 이 적재한 outbox payload 에 Toss 응답과 선점 항목이 담긴다")
    void confirm_outbox_payload에_Toss응답과_선점항목이_담긴다() throws Exception {
        // 플래시 상품만 Redis 선점을 하므로 reservedItems 에 항목이 남는다(일반 상품은 예약하지 않는다)
        jdbc.update("UPDATE product SET flash_sale = true WHERE id = ?", productId);
        productStockRedisRepository.initStock(productId, 10);

        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        String payload = jdbc.queryForObject(
                "SELECT payload FROM payment_outbox WHERE aggregate_id = ?", String.class, payment.getId());
        JsonNode json = objectMapper.readTree(payload);
        assertThat(json.get("paymentId").asText()).isEqualTo(payment.getId().toString());
        assertThat(json.get("orderId").asText()).isEqualTo(payment.getOrderId().toString());
        assertThat(json.get("amount").asInt()).isEqualTo(PaymentFixture.PRICE);
        assertThat(json.get("tossResponse").get("paymentKey").asText()).isEqualTo(paymentKey);
        assertThat(json.get("reservedItems")).hasSize(1);
        assertThat(json.get("reservedItems").get(0).get("productId").asText()).isEqualTo(productId.toString());
        assertThat(json.get("reservedItems").get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(json.get("reservedItems").get(0).get("mode").asText()).as("예약 방식").isEqualTo("REDIS");
    }
}
