package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockLedgerRepository;
import groom.backend.application.product.StockLedgerService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.product.model.enums.StockMode;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 원장에 SALE 이 남은 뒤 실패하면 REVERSAL 행이 정확히 1번 쓰이고, REDIS 모드에서는 Redis 선점도 돌려주는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=300",
        "payment.confirm.poll-interval-ms=50",
        "payment.compensation.retry-delay-ms=600000"
})
@DisplayName("원장 REVERSAL 기록")
class StockLedgerReversalTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;
    @MockBean
    private PaymentNotificationService paymentNotificationService;
    @MockBean
    private TossPaymentClient tossPaymentClient;
    @SpyBean
    private StockLedgerRepository stockLedgerRepository;

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private FlashStockReservation flashStockReservation;
    @Autowired
    private StockLedgerService stockLedgerService;
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
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
    }

    @AfterEach
    void tearDown() {
        reset(stockLedgerRepository);
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        fixture.cleanup(payment, productId);
    }

    private String redisValue() {
        return redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
    }

    @Test
    @DisplayName("REDIS 모드에서 후처리가 실패하면 REVERSAL 이 1번만 쓰이고 Redis 선점이 돌아오며 결제의 원장 합은 0 이다")
    void 후처리실패_REDIS모드_REVERSAL_1행과_Redis복원_원장합_0() throws Exception {
        productStockRedisRepository.initStock(productId, 10);
        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);
        assertThat(redisValue()).isEqualTo("9");
        doThrow(new IllegalStateException("post-process down")).when(stockLedgerRepository).findSaleProductIds(any());
        String payload = jdbc.queryForObject(
                "SELECT payload FROM payment_outbox WHERE aggregate_id = ?", String.class, payment.getId());
        PaymentApprovedEvent event = objectMapper.readValue(payload, PaymentApprovedEvent.class);

        paymentApplicationService.applyApprovedEvent(event);
        // 실패 경로가 한 번 더 돌아도(재대사 · 중복 호출) 행과 Redis 복원은 한 번뿐이다
        flashStockReservation.releaseAfterRecord(payment.getId(), event.reservedItems());

        List<Map<String, Object>> rows = fixture.ledgerRows(payment.getId());
        assertThat(rows).extracting(r -> r.get("kind")).containsExactly("SALE", "REVERSAL");
        assertThat(rows.get(1)).containsEntry("qty", -1).containsEntry("applied", false);
        assertThat(fixture.ledgerSumOf(payment.getId())).isZero();
        assertThat(redisValue()).as("Redis 선점 복원").isEqualTo("10");
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("DB 모드 진입 예약 뒤 Toss 가 실패하면 REVERSAL 이 1번만 쓰이고 Redis 키는 손대지 않는다")
    void Toss실패_DB모드_진입예약_REVERSAL_1행_Redis키는_그대로() {
        productStockRedisRepository.initStock(productId, 10);
        stockLedgerService.switchToDb(productId);
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_PG_REJECTED));

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE))
                .isInstanceOf(BusinessException.class);
        // 실패 경로가 한 번 더 돌아도 REVERSAL 은 하나다
        flashStockReservation.releaseBeforeRecord(payment.getId(),
                List.of(new PaymentApprovedEvent.ReservedItem(productId, 1, StockMode.DB)));

        List<Map<String, Object>> rows = fixture.ledgerRows(payment.getId());
        assertThat(rows).extracting(r -> r.get("kind")).containsExactly("SALE", "REVERSAL");
        assertThat(rows.get(0)).containsEntry("applied", true).containsEntry("qty", 1);
        assertThat(rows.get(1)).containsEntry("applied", false).containsEntry("qty", -1);
        assertThat(fixture.ledgerSumOf(payment.getId())).isZero();
        assertThat(redisValue()).as("DB 모드 진입은 Redis 를 쓰지 않았다").isEqualTo("10");
        assertThat(fixture.stockOf(productId) - fixture.unappliedSumOf(productId))
                .as("남은 재고(stock − 미적용 합)").isEqualTo(10);
    }

    @Test
    @DisplayName("DONE 플래시 결제를 취소하면 REVERSAL 이 1번만 쓰이고 원장 합은 0 이며 Redis 는 수량만큼 한 번만 돌아온다 (Ruling R117-2)")
    void 취소_DONE_플래시결제_REVERSAL_1행_원장합_0_Redis_한번만_복원() throws Exception {
        productStockRedisRepository.initStock(productId, 10);
        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);
        String payload = jdbc.queryForObject(
                "SELECT payload FROM payment_outbox WHERE aggregate_id = ?", String.class, payment.getId());
        PaymentApprovedEvent event = objectMapper.readValue(payload, PaymentApprovedEvent.class);
        paymentApplicationService.applyApprovedEvent(event);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(redisValue()).isEqualTo("9");

        paymentApplicationService.cancelPayment(payment.getId(), "단순 변심");
        // 취소 경로가 한 번 더 돌아도(재시도) REVERSAL 과 Redis 복원은 한 번뿐이다
        flashStockReservation.releaseAfterRecord(payment.getId(), event.reservedItems());

        assertThat(fixture.ledgerRows(payment.getId())).extracting(r -> r.get("kind")).containsExactly("SALE", "REVERSAL");
        assertThat(fixture.ledgerSumOf(payment.getId())).isZero();
        assertThat(redisValue()).as("Redis 선점 복원은 한 번").isEqualTo("10");
        assertThat(fixture.stockOf(productId)).as("상품 행에 직접 더하지 않는다").isEqualTo(10);
    }
}
