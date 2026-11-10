package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import groom.backend.application.payment.event.PaymentApprovedEvent;
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

// 플래시 세일 상품의 승인 트랜잭션(TX1)이 Payment READY + Outbox 와 함께 원장 SALE 행을 남기고 상품 행은 건드리지 않는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=300",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("플래시 세일 승인 트랜잭션의 원장 기록")
class StockLedgerRecordTest {

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
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private PaymentFixture fixture;
    private UUID productId;
    private UUID secondProductId;
    private Payment payment;
    private String paymentKey;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.flashProduct(10);
        secondProductId = fixture.flashProduct(5);
        productStockRedisRepository.initStock(productId, 10);
        productStockRedisRepository.initStock(secondProductId, 5);
        paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + secondProductId);
        fixture.cleanup(payment, productId);
        fixture.deleteProduct(secondProductId);
    }

    @Test
    @DisplayName("마이그레이션 V22 가 flash_sale 기본 false · stock_mode 기본 REDIS 컬럼과 stock_ledger 표를 만든다")
    void 마이그레이션_V22_컬럼_기본값과_원장표가_있다() {
        payment = fixture.payment(productId, 1);

        assertThat(jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns WHERE table_name='product' AND column_name='flash_sale'",
                String.class)).isEqualTo("false");
        assertThat(jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns WHERE table_name='product' AND column_name='stock_mode'",
                String.class)).startsWith("'REDIS'");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='stock_ledger'",
                Integer.class)).isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("REDIS 모드 플래시 상품은 confirm 이 READY · Outbox · 항목당 SALE 원장 1행을 남기고 product.stock 은 그대로다")
    void confirm_REDIS모드_플래시상품_READY와_Outbox와_SALE원장_1행을_남기고_재고는_그대로다() {
        payment = fixture.payment(productId, 2);
        // 수량 2 주문의 금액은 2000 이다
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE * 2));

        Payment result = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE * 2);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE aggregate_id = ?",
                Integer.class, payment.getId())).isEqualTo(1);
        List<Map<String, Object>> rows = fixture.ledgerRows(payment.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("kind", "SALE").containsEntry("qty", 2)
                .containsEntry("applied", false).containsEntry("product_id", productId);
        assertThat(fixture.stockOf(productId)).as("product.stock").isEqualTo(10);
        assertThat(fixture.unappliedSumOf(productId)).isEqualTo(2);
    }

    @Test
    @DisplayName("항목이 둘인 주문은 항목마다 SALE 원장 1행이 남는다")
    void confirm_항목이_둘인_주문_항목마다_SALE_1행이_남는다() {
        payment = fixture.payment(productId, 1, secondProductId, 3);
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE * 4));

        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE * 4);

        assertThat(fixture.ledgerRows(payment.getId())).hasSize(2)
                .allSatisfy(row -> assertThat(row).containsEntry("kind", "SALE").containsEntry("applied", false));
        assertThat(fixture.unappliedSumOf(productId)).isEqualTo(1);
        assertThat(fixture.unappliedSumOf(secondProductId)).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 결제의 TX1 메서드를 두 번 불러도 SALE 행은 항목당 1개다")
    void recordApprovedInTx_두번_호출해도_SALE은_항목당_1개다() {
        payment = fixture.payment(productId, 1);
        var response = PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE);
        var items = List.of(new PaymentApprovedEvent.ReservedItem(productId, 1));

        paymentApplicationService.recordApprovedInTx(payment.getId(), response, items);
        paymentApplicationService.recordApprovedInTx(payment.getId(), response, items);

        assertThat(fixture.ledgerRows(payment.getId())).hasSize(1);
        assertThat(fixture.unappliedSumOf(productId)).isEqualTo(1);
    }
}
