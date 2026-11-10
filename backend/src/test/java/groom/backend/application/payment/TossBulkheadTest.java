package groom.backend.application.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Toss 승인 동시 호출을 bulkhead toss 가 막는지 본다 - 한도를 넘은 호출은 재시도도 회로 실패도 아니고 PAYMENT_PG_BUSY(503) 로 끝나며 플래시 재고 선점이 돌아간다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Toss 승인 bulkhead")
class TossBulkheadTest {

    private static final String CONFIRM_PATH = "/v1/payments/confirm";
    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("payment.toss.api-url", WIRE_MOCK::baseUrl);
        registry.add("payment.toss.secret-key", () -> "test_sk_dummy");
        registry.add("spring.kafka.streams.auto-startup", () -> "false");
        registry.add("payment.consumer.auto-startup", () -> "false");
        registry.add("payment.confirm.wait-timeout-ms", () -> "300");
        registry.add("payment.confirm.poll-interval-ms", () -> "50");
        // 운영값은 50 - 시험은 동시 3건으로 한도 초과를 만들려고 2 로 줄인다
        registry.add("resilience4j.bulkhead.instances.toss.max-concurrent-calls", () -> "2");
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;
    @MockBean
    private PaymentNotificationService paymentNotificationService;

    @Autowired
    private TossPaymentClient tossPaymentClient;
    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private BulkheadRegistry bulkheadRegistry;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    private ProductStockRedisRepository stockRedisRepository;
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
    private Payment payment;
    private UUID productId;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        circuitBreakerRegistry.circuitBreaker("toss").reset();
    }

    @AfterEach
    void tearDown() {
        if (productId != null) {
            redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
            fixture.cleanup(payment, productId);
        }
    }

    private static TossPaymentConfirmRequest request() {
        return new TossPaymentConfirmRequest("pk-" + UUID.randomUUID(), UUID.randomUUID().toString(), 1000);
    }

    private static void stubConfirm(int delayMs) {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CONFIRM_PATH)).willReturn(aResponse().withFixedDelay(delayMs).withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"paymentKey\":\"pk\",\"status\":\"DONE\"}")));
    }

    private record Outcome(Throwable error, long elapsedMs) {
    }

    @Test
    @DisplayName("동시 3건 중 정확히 1건이 300ms 안에 PAYMENT_PG_BUSY(503) 로 거절되고 Toss 는 2번만 받으며 회로 실패로 세지 않는다")
    void confirmPayment_한도_초과_1건이_즉시_BUSY로_거절된다() throws Exception {
        stubConfirm(1000);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Callable<Outcome> call = () -> {
                start.await();
                long begin = System.nanoTime();
                try {
                    tossPaymentClient.confirmPayment(request(), "idem-" + UUID.randomUUID());
                    return new Outcome(null, (System.nanoTime() - begin) / 1_000_000);
                } catch (Throwable e) {
                    return new Outcome(e, (System.nanoTime() - begin) / 1_000_000);
                }
            };
            futures.add(pool.submit(call));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        List<Outcome> rejected = outcomes.stream().filter(o -> o.error() != null).toList();
        assertThat(rejected).as("거절된 호출").hasSize(1);
        assertThat(rejected.get(0).error()).isInstanceOf(BusinessException.class);
        BusinessException busy = (BusinessException) rejected.get(0).error();
        assertThat(busy.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_PG_BUSY);
        assertThat(busy.getErrorCode().getStatus().value()).isEqualTo(503);
        assertThat(rejected.get(0).elapsedMs()).as("거절까지 걸린 시간(ms)").isLessThan(300);
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(CONFIRM_PATH)));
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("toss");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).as("toss 회로 실패 수").isZero();
    }

    @Test
    @DisplayName("거절된 플래시 상품 결제는 Redis 재고 키를 요청 전 값으로 남긴다")
    void confirmPayment_BUSY_플래시상품_Redis재고가_요청전_값이다() {
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        stockRedisRepository.initStock(productId, 10);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("toss");
        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        try {
            assertThatThrownBy(() -> paymentApplicationService.confirmPayment(
                    "pk-" + UUID.randomUUID(), payment.getOrderId(), PaymentFixture.PRICE))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_PG_BUSY));
        } finally {
            bulkhead.releasePermission();
            bulkhead.releasePermission();
        }

        assertThat(redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId)).isEqualTo("10");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(CONFIRM_PATH)));
    }
}
