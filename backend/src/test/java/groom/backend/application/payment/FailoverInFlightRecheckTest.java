package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockLedgerService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionTemplate;

// Redis 에서 선점한 결제가 TX1 에 닿기 전에 상품이 DB 모드로 바뀌면 TX1 이 조건부 차감으로 다시 판정하는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=300",
        "payment.confirm.poll-interval-ms=50",
        "payment.compensation.retry-delay-ms=600000"
})
@DisplayName("전환 중 인플라이트 결제의 TX1 재판정")
class FailoverInFlightRecheckTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;
    @MockBean
    private PaymentNotificationService paymentNotificationService;
    @MockBean
    private TossPaymentClient tossPaymentClient;

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private StockLedgerService stockLedgerService;
    @Autowired
    private TransactionTemplate transactionTemplate;
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
    private Payment payment;
    private String paymentKey;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        paymentKey = "pk-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("Redis 로 선점한 결제의 TX1 이 DB 모드를 만나면 미적용 행이 아니라 조건부 차감 + SALE(applied=true) 를 남긴다")
    void recordApprovedInTx_선점뒤_DB모드로_바뀌면_조건부차감과_SALE_applied를_남긴다() {
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        stockLedgerService.switchToDb(productId);

        paymentApplicationService.recordApprovedInTx(payment.getId(),
                PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE),
                List.of(new PaymentApprovedEvent.ReservedItem(productId, 1)));

        assertThat(fixture.stockOf(productId)).isEqualTo(9);
        assertThat(fixture.unappliedSumOf(productId)).isZero();
        assertThat(fixture.ledgerRows(payment.getId())).hasSize(1)
                .allSatisfy(r -> assertThat(r).containsEntry("kind", "SALE").containsEntry("applied", true));
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("READY");
    }

    @Test
    @DisplayName("재고가 모자라면 TX1 이 실패해 보상 행과 Payment FAILED 로 이어지고 product.stock 은 0 아래로 가지 않는다")
    void confirm_Toss호출중_전환되고_재고가_모자라면_보상경로로_가고_재고는_음수가_되지_않는다() {
        productId = fixture.flashProduct(1);
        payment = fixture.payment(productId, 1);
        productStockRedisRepository.initStock(productId, 1);
        // Toss 응답을 기다리는 사이 전환이 일어나고 재고가 다른 판매로 소진된 상황
        given(tossPaymentClient.confirmPayment(any(), anyString())).willAnswer(invocation -> {
            stockLedgerService.switchToDb(productId);
            jdbc.update("UPDATE product SET stock = 0 WHERE id = ?", productId);
            return PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE);
        });

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

        assertThat(fixture.compensationCountOf(payment.getId())).as("보상 행").isEqualTo(1);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
        assertThat(fixture.stockOf(productId)).isZero();
        assertThat(fixture.ledgerRows(payment.getId())).as("TX1 이 롤백돼 원장 행이 없다").isEmpty();
    }

    @Test
    @DisplayName("TX1 이 열려 있는 동안 전환은 기다리고, TX1 이 커밋되면 끝나며 그 TX1 이 쓴 행을 접어 넣는다")
    void switchToDb_진행중인_TX1이_끝나길_기다렸다가_그_행을_접어_넣는다() throws Exception {
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        CountDownLatch recorded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> tx1 = pool.submit(() -> transactionTemplate.execute(status -> {
                stockLedgerService.recordSale(payment.getId(), productId, 1);
                recorded.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(recorded.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> switching = pool.submit(() -> stockLedgerService.switchToDb(productId));

            Thread.sleep(500);
            assertThat(switching.isDone()).as("TX1 이 열려 있는 동안 전환은 끝나지 않는다").isFalse();
            assertThat(fixture.stockModeOf(productId)).isEqualTo("REDIS");

            release.countDown();
            tx1.get(10, TimeUnit.SECONDS);
            switching.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(fixture.stockModeOf(productId)).isEqualTo("DB");
        assertThat(fixture.stockOf(productId)).as("TX1 이 쓴 행이 재고에 접혀 들어갔다").isEqualTo(9);
        assertThat(fixture.unappliedSumOf(productId)).isZero();
        assertThat(fixture.ledgerRows(payment.getId())).hasSize(1)
                .allSatisfy(r -> assertThat(r).containsEntry("applied", true));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
