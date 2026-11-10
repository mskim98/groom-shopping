package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockLedgerRepository;
import groom.backend.application.product.StockLedgerService;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 사용자 취소의 REVERSAL 과 복귀(returnToRedis)가 어떤 순서로 만나도 Redis 키가 stock − 미적용 합으로 끝나는지 본다
// 순서는 REVERSAL 을 쓰는 insertReversals 가 끝난 직후의 훅으로 강제한다 - 취소 트랜잭션이 열려 있는 한가운데다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=300",
        "payment.confirm.poll-interval-ms=50",
        "payment.compensation.retry-delay-ms=600000"
})
@DisplayName("취소 REVERSAL ↔ 복귀 경합")
class CancelReturnRaceTest {

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
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @BeforeEach
    void setUp() throws Exception {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        String paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
        // DONE 플래시 결제 하나: Redis 키 9, 미적용 SALE 1행
        productStockRedisRepository.initStock(productId, 10);
        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);
        String payload = jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE aggregate_id = ?", String.class, payment.getId());
        paymentApplicationService.applyApprovedEvent(objectMapper.readValue(payload, PaymentApprovedEvent.class));
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(redisValue()).isEqualTo("9");
        // 장애 전환: 미적용 SALE 이 stock 에 접혀 들어가고 DB 모드가 된다
        stockLedgerService.switchToDb(productId);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        reset(stockLedgerRepository);
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        fixture.cleanup(payment, productId);
    }

    private String redisValue() {
        return redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
    }

    private String expectedKey() {
        return String.valueOf(fixture.stockOf(productId) - fixture.unappliedSumOf(productId));
    }

    // insertReversals 가 끝난 직후(취소 트랜잭션이 열려 있다)에 hook 을 실행한다
    private void afterReversalInsert(Runnable hook) {
        doAnswer(invocation -> {
            Object rows = invocation.callRealMethod();
            hook.run();
            return rows;
        }).when(stockLedgerRepository).insertReversals(any());
    }

    @Test
    @DisplayName("(a) 취소가 DB 모드에서 커밋되고 복귀가 먼저 끝난 뒤 커밋 뒤 단계가 돌아도 키는 stock − 미적용 합이다 (취소 수량이 두 번 더해지지 않는다)")
    void 취소_DB모드_커밋_복귀_커밋뒤단계_키는_stock_빼기_미적용합() {
        afterReversalInsert(() -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 서비스 자신의 커밋 뒤 단계보다 먼저 등록돼 먼저 돈다. 커밋된 트랜잭션에 합류하지 않게 다른 스레드에서 돌린다
                try {
                    assertThat(pool.submit(() -> stockLedgerService.returnToRedis(productId, 0)).get(10, TimeUnit.SECONDS)).isTrue();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }));

        paymentApplicationService.cancelPayment(payment.getId(), "단순 변심");

        assertThat(fixture.stockModeOf(productId)).isEqualTo("REDIS");
        assertThat(redisValue()).isEqualTo(expectedKey()).isEqualTo("10");
    }

    @Test
    @DisplayName("(b) 복귀가 먼저 커밋되고 취소가 나중에 오면 취소 수량이 한 번 더해져 키는 stock − 미적용 합이다")
    void 복귀_커밋_취소_커밋뒤단계_키는_stock_빼기_미적용합() {
        assertThat(stockLedgerService.returnToRedis(productId, 0)).isTrue();
        assertThat(redisValue()).isEqualTo("9");

        paymentApplicationService.cancelPayment(payment.getId(), "단순 변심");

        assertThat(redisValue()).isEqualTo(expectedKey()).isEqualTo("10");
    }

    @Test
    @DisplayName("(c) 취소 트랜잭션이 열려 있는 동안 복귀는 기다리고, 취소가 커밋되면 끝나며 키는 stock − 미적용 합이다")
    void 취소_트랜잭션이_열려있으면_복귀는_기다렸다가_키는_stock_빼기_미적용합() throws Exception {
        AtomicReference<Future<Boolean>> returning = new AtomicReference<>();
        afterReversalInsert(() -> {
            returning.set(pool.submit(() -> stockLedgerService.returnToRedis(productId, 0)));
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(returning.get().isDone()).as("취소 트랜잭션이 열려 있는 동안 복귀는 끝나지 않는다").isFalse();
            assertThat(fixture.stockModeOf(productId)).isEqualTo("DB");
        });

        paymentApplicationService.cancelPayment(payment.getId(), "단순 변심");

        assertThat(returning.get().get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(redisValue()).isEqualTo(expectedKey()).isEqualTo("10");
    }
}
