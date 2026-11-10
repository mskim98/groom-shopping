package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
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

// 플래시 상품 결제 진입이 초당 한도(시험에서는 10초에 2건)를 넘으면 Redis 도 원장도 건드리지 않고 429 로 끝나는지, 플래시가 아닌 주문은 제한받지 않는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=300",
        "payment.confirm.poll-interval-ms=50",
        // 운영값은 1초에 2000건 - 시험은 세 번째 요청이 걸리도록 10초에 2건으로 줄인다
        "resilience4j.ratelimiter.instances.flash-entry.limit-for-period=2",
        "resilience4j.ratelimiter.instances.flash-entry.limit-refresh-period=10s"
})
@DisplayName("플래시 진입 속도 제한")
class FlashEntryRateLimitTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;
    @MockBean
    private PaymentNotificationService paymentNotificationService;
    @MockBean
    private TossPaymentClient tossPaymentClient;
    @SpyBean
    private ProductStockRedisRepository stockRedisRepository;

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;
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
    private UUID flashId;
    private UUID plainId;
    private Payment[] payments = new Payment[3];
    private Payment plainPayment;

    @BeforeEach
    void setUp() {
        // 한도 상태는 컨텍스트 안에서 공유되므로 시험마다 새로 만든다 - 레지스트리에서 지우면 다음 조회가 설정대로 다시 만든다
        rateLimiterRegistry.remove("flash-entry");
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        flashId = fixture.flashProduct(10);
        stockRedisRepository.initStock(flashId, 10);
        for (int i = 0; i < payments.length; i++) {
            payments[i] = fixture.payment(flashId, 1);
        }
        plainId = fixture.product(10);
        plainPayment = fixture.payment(plainId, 1);
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willAnswer(call -> PaymentFixture.tossResponse(call.getArgument(1), PaymentFixture.PRICE));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + flashId);
        for (Payment p : payments) {
            fixture.cleanupPaymentOnly(p);
        }
        fixture.cleanup(plainPayment, plainId);
        fixture.deleteProduct(flashId);
    }

    private String key(Payment payment) {
        return "pk-" + payment.getId();
    }

    @Test
    @DisplayName("한도 2 에서 세 번째 플래시 확정은 429 이고 Redis 재고 키에는 선점 2건만 남으며 Redis 호출도 원장 행도 없다")
    void confirmPayment_한도_초과_세번째는_429이고_Redis와_원장을_건드리지_않는다() {
        paymentApplicationService.confirmPayment(key(payments[0]), payments[0].getOrderId(), PaymentFixture.PRICE);
        paymentApplicationService.confirmPayment(key(payments[1]), payments[1].getOrderId(), PaymentFixture.PRICE);
        clearInvocations(stockRedisRepository);

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(
                key(payments[2]), payments[2].getOrderId(), PaymentFixture.PRICE))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FLASH_ENTRY_LIMITED);
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(429);
                });

        assertThat(redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + flashId)).isEqualTo("8");
        verify(stockRedisRepository, never()).preReserve(any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(fixture.ledgerRows(payments[2].getId())).isEmpty();
    }

    @Test
    @DisplayName("플래시 상품이 없는 주문은 한도를 다 쓴 뒤에도 제한받지 않는다")
    void confirmPayment_플래시_아닌_주문은_제한받지_않는다() {
        paymentApplicationService.confirmPayment(key(payments[0]), payments[0].getOrderId(), PaymentFixture.PRICE);
        paymentApplicationService.confirmPayment(key(payments[1]), payments[1].getOrderId(), PaymentFixture.PRICE);

        Payment result = paymentApplicationService.confirmPayment(
                key(plainPayment), plainPayment.getOrderId(), PaymentFixture.PRICE);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
    }
}
