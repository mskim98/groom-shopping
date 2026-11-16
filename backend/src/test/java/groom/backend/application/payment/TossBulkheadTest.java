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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

// Toss 승인 동시 호출을 bulkhead toss 가 막는지 본다 - 한도를 넘은 호출은 자리가 나길 기다려 승인되고, 대기 상한을 넘으면 재시도도 회로 실패도 아니고 PAYMENT_PG_BUSY(503) 로 끝나며 플래시 재고 선점이 돌아간다
// 자리를 기다린 시간은 회로가 재는 Toss 호출 시간에 들어가지 않는다(적용 순서 retry < bulkhead < circuitbreaker)
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
        // 운영값은 60 · 10초 - 시험은 자리 1개 · 대기 2초로 줄여 기다림과 대기 초과를 짧게 만든다
        registry.add("resilience4j.bulkhead.instances.toss.max-concurrent-calls", () -> "1");
        registry.add("resilience4j.bulkhead.instances.toss.max-wait-duration", () -> "2s");
        // 자리를 기다린 시간(최대 800ms)이 느린 호출 기준을 넘도록 운영값 10초를 500ms 로 줄인다
        registry.add("resilience4j.circuitbreaker.instances.toss.slow-call-duration-threshold", () -> "500ms");
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

    private static List<Outcome> confirmConcurrently(int calls, TossPaymentClient client) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            Callable<Outcome> call = () -> {
                start.await();
                return timedConfirm(client);
            };
            futures.add(pool.submit(call));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private static Outcome timedConfirm(TossPaymentClient client) {
        long begin = System.nanoTime();
        try {
            client.confirmPayment(request(), "idem-" + UUID.randomUUID());
            return new Outcome(null, (System.nanoTime() - begin) / 1_000_000);
        } catch (Throwable e) {
            return new Outcome(e, (System.nanoTime() - begin) / 1_000_000);
        }
    }

    @Test
    @DisplayName("자리 1개에 동시 2건이면 둘 다 승인되고 뒤 호출은 앞 호출이 끝나길 기다린 만큼 늦다")
    void confirmPayment_한도_초과_호출이_자리를_기다려_승인된다() throws Exception {
        stubConfirm(300);

        List<Outcome> outcomes = confirmConcurrently(2, tossPaymentClient);

        assertThat(outcomes).as("두 호출 모두 성공").allSatisfy(o -> assertThat(o.error()).isNull());
        // 뒤 호출 = 앞 호출 300ms 를 기다림 + 자기 호출 300ms
        assertThat(outcomes.stream().mapToLong(Outcome::elapsedMs).max().orElseThrow()).as("늦은 호출 소요(ms)").isGreaterThanOrEqualTo(550);
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(CONFIRM_PATH)));
        assertThat(circuitBreakerRegistry.circuitBreaker("toss").getMetrics().getNumberOfFailedCalls()).as("toss 회로 실패 수").isZero();
    }

    @Test
    @DisplayName("대기 상한(2초)까지 자리가 안 나면 PAYMENT_PG_BUSY(503) 로 끝나고 재시도로 다시 기다리지 않으며 Toss 는 부르지 않고 회로 실패로 세지 않는다")
    void confirmPayment_대기_상한을_넘으면_BUSY로_거절된다() {
        stubConfirm(0);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("toss");
        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        Outcome outcome;
        try {
            outcome = timedConfirm(tossPaymentClient);
        } finally {
            bulkhead.releasePermission();
        }

        assertThat(outcome.error()).isInstanceOf(BusinessException.class);
        BusinessException busy = (BusinessException) outcome.error();
        assertThat(busy.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_PG_BUSY);
        assertThat(busy.getErrorCode().getStatus().value()).isEqualTo(503);
        // 재시도가 한 번 더 기다리면 4초 이상이다
        assertThat(outcome.elapsedMs()).as("거절까지 걸린 시간(ms)").isBetween(1800L, 3500L);
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(CONFIRM_PATH)));
        assertThat(circuitBreakerRegistry.circuitBreaker("toss").getMetrics().getNumberOfFailedCalls()).as("toss 회로 실패 수").isZero();
    }

    @Test
    @DisplayName("자리를 800ms 기다린 뒤 빠르게 끝난 승인은 느린 호출 기준(500ms)을 넘는 호출로 세지 않는다")
    void confirmPayment_자리_대기_시간은_회로_느린호출에_들지_않는다() throws Exception {
        stubConfirm(0);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("toss");
        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        ScheduledExecutorService releaser = Executors.newSingleThreadScheduledExecutor();
        Outcome outcome;
        try {
            releaser.schedule(bulkhead::releasePermission, 800, TimeUnit.MILLISECONDS);
            outcome = timedConfirm(tossPaymentClient);
        } finally {
            releaser.shutdown();
            releaser.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(outcome.error()).as("대기 뒤 승인 성공").isNull();
        assertThat(outcome.elapsedMs()).as("자리를 기다린 시간(ms)").isGreaterThanOrEqualTo(700);
        CircuitBreaker.Metrics metrics = circuitBreakerRegistry.circuitBreaker("toss").getMetrics();
        assertThat(metrics.getNumberOfSuccessfulCalls()).as("toss 회로 성공 수").isEqualTo(1);
        assertThat(metrics.getNumberOfSlowCalls()).as("toss 회로 느린 호출 수").isZero();
    }

    @Test
    @DisplayName("거절된 플래시 상품 결제는 Redis 재고 키를 요청 전 값으로 남긴다")
    void confirmPayment_BUSY_플래시상품_Redis재고가_요청전_값이다() {
        productId = fixture.flashProduct(10);
        payment = fixture.payment(productId, 1);
        stockRedisRepository.initStock(productId, 10);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("toss");
        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        try {
            assertThatThrownBy(() -> ConfirmAsOwner.confirm(paymentApplicationService, payment,
                    "pk-" + UUID.randomUUID(), PaymentFixture.PRICE))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_PG_BUSY));
        } finally {
            bulkhead.releasePermission();
        }

        assertThat(redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId)).isEqualTo("10");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(CONFIRM_PATH)));
    }
}
