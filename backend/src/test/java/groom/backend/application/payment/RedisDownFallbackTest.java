package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// Redis 선점이 실패(예외 · 서킷 OPEN · 키 없음)해도 플래시 상품 결제가 503 도 재고 검사 생략도 없이 DB 모드로 이어지는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=300",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("Redis 장애 시 DB 모드 전환")
class RedisDownFallbackTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;
    @MockBean
    private PaymentNotificationService paymentNotificationService;
    @MockBean
    private TossPaymentClient tossPaymentClient;
    @SpyBean
    private ProductStockRedisRepository productStockRedisRepository;

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
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
    private Payment payment;
    private Payment secondPayment;
    private String paymentKey;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
        circuitBreakerRegistry.circuitBreaker("redis-stock").reset();
    }

    @AfterEach
    void tearDown() {
        reset(productStockRedisRepository);
        circuitBreakerRegistry.circuitBreaker("redis-stock").reset();
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        if (secondPayment != null) {
            fixture.cleanupPaymentOnly(secondPayment);
        }
        fixture.cleanup(payment, productId);
    }

    private void redisThrows() {
        doThrow(new RedisConnectionFailureException("redis down")).when(productStockRedisRepository)
                .preReserve(any(), anyInt());
    }

    @Test
    @DisplayName("Redis 선점이 예외를 던지면 confirm 이 전환 트랜잭션 → DB 모드 조건부 UPDATE + SALE(applied=true) 로 성공한다")
    void confirm_Redis예외_DB모드로_전환하고_재고_조건부UPDATE와_SALE_applied로_성공한다() {
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        redisThrows();

        Payment result = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(fixture.stockModeOf(productId)).isEqualTo("DB");
        assertThat(fixture.stockOf(productId)).isEqualTo(9);
        assertThat(fixture.ledgerRows(payment.getId())).hasSize(1)
                .allSatisfy(r -> assertThat(r).containsEntry("kind", "SALE").containsEntry("applied", true));
    }

    @Test
    @DisplayName("전환은 미적용 원장 행을 먼저 재고에 적용한다 - 재고 10 · 미적용 SALE 3 이면 전환 뒤 재고 7")
    void confirm_전환_미적용_원장행을_먼저_적용한다() {
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        jdbc.update("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) VALUES (?, ?, 3, 'SALE', false)",
                UUID.randomUUID(), productId);
        redisThrows();

        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        // 7 에서 이번 주문 1개를 DB 모드 진입으로 낮춘 값이다
        assertThat(fixture.stockOf(productId)).isEqualTo(6);
        assertThat(fixture.unappliedSumOf(productId)).isZero();
    }

    @Test
    @DisplayName("재고 1 에 순차 confirm 두 번이면 성공 하나와 재고 부족 실패 하나다")
    void confirm_재고1_순차_두번_성공1_재고부족1() {
        productId = fixture.flashProduct(1);
        payment = fixture.payment(productId, 1);
        secondPayment = fixture.payment(productId, 1);
        redisThrows();

        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);
        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(
                "pk-second-" + UUID.randomUUID(), secondPayment.getOrderId(), PaymentFixture.PRICE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

        assertThat(fixture.stockOf(productId)).isZero();
        assertThat(fixture.ledgerRows(secondPayment.getId())).as("실패한 결제는 원장 행이 없다").isEmpty();
    }

    @Test
    @DisplayName("redis-stock 서킷이 OPEN 이면 Redis 를 부르지 않고 DB 모드로 성공한다")
    void confirm_서킷OPEN_Redis를_부르지_않고_DB모드로_성공한다() {
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        circuitBreakerRegistry.circuitBreaker("redis-stock").transitionToOpenState();

        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        verify(productStockRedisRepository, never()).preReserve(any(), anyInt());
        assertThat(fixture.stockModeOf(productId)).isEqualTo("DB");
        assertThat(fixture.stockOf(productId)).isEqualTo(9);
    }

    @Test
    @DisplayName("Redis 키가 없어 스크립트가 -2 를 돌려줘도 재고 검사를 건너뛰지 않고 DB 모드로 간다")
    void confirm_키없음_마이너스2_재고검사를_건너뛰지_않고_DB모드로_간다() {
        productId = fixture.flashProduct(0);
        payment = fixture.payment(productId, 1);
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

        assertThat(fixture.stockModeOf(productId)).isEqualTo("DB");
        assertThat(fixture.stockOf(productId)).isZero();
    }

    @Test
    @DisplayName("DB 모드 + Redis 정상 + 오래된 키가 남아 있어도 confirm 은 Redis 키를 바꾸지 않는다")
    void confirm_DB모드_Redis정상_오래된키_Redis키를_바꾸지_않는다() {
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        jdbc.update("UPDATE product SET stock_mode = 'DB', stock_mode_changed_at = now() WHERE id = ?", productId);
        productStockRedisRepository.initStock(productId, 99);

        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        assertThat(redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId))
                .isEqualTo("99");
        assertThat(fixture.stockOf(productId)).isEqualTo(9);
        verify(productStockRedisRepository, never()).preReserve(any(), anyInt());
    }
}
