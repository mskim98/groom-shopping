package groom.backend.infrastructure.resilience;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import groom.backend.application.payment.PaymentCompensationService;
import groom.backend.infrastructure.payment.TossCircuitOpenException;
import groom.backend.infrastructure.payment.TossPaymentClientImpl;
import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

// 한 인스턴스가 연 회로를 Redis 키(cb:{name}:open_until)로 다른 인스턴스가 보고 Toss 를 부르지 않는지, Redis 가 죽으면 자기 회로만 따르는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("공유 서킷 플래그")
class SharedCircuitFlagTest {

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
        // 배경 보상 배치가 시험 행을 가져가지 않게 늦추고, 시험이 retryPendingCompensations 를 직접 부른다
        registry.add("payment.compensation.retry-delay-ms", () -> "600000");
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private groom.backend.infrastructure.payment.TossPaymentClient tossPaymentClient;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    private StringRedisTemplate redis;
    @SpyBean
    private PaymentCompensationService compensationService;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    @Qualifier("tossCancelRestTemplate")
    private RestTemplate cancelRestTemplate;
    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        for (String name : List.of("toss", "toss-cancel", "db-ledger")) {
            circuitBreakerRegistry.circuitBreaker(name).reset();
            redis.delete(SharedCircuitFlag.key(name));
        }
    }

    @AfterEach
    void tearDown() {
        for (String name : List.of("toss", "toss-cancel", "db-ledger")) {
            circuitBreakerRegistry.circuitBreaker(name).reset();
            redis.delete(SharedCircuitFlag.key(name));
        }
    }

    private static TossPaymentConfirmRequest request() {
        return new TossPaymentConfirmRequest("pk-" + UUID.randomUUID(), UUID.randomUUID().toString(), 1000);
    }

    private void openForOthers(String name, long millis) {
        redis.opsForValue().set(SharedCircuitFlag.key(name), String.valueOf(System.currentTimeMillis() + millis),
                Duration.ofMillis(millis));
    }

    @Test
    @DisplayName("toss · toss-cancel · db-ledger 가 OPEN 이 되면 열림이 끝나는 시각(epoch ms)을 같은 만료로 쓴다")
    void OPEN_전이_open_until_키를_만료와_함께_쓴다() {
        for (String name : List.of("toss", "toss-cancel", "db-ledger")) {
            CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(name);
            long waitMs = breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
            long before = System.currentTimeMillis();

            breaker.transitionToOpenState();

            String value = redis.opsForValue().get(SharedCircuitFlag.key(name));
            assertThat(value).as(name + " 키").isNotNull();
            assertThat(Long.parseLong(value)).isBetween(before + waitMs - 200, System.currentTimeMillis() + waitMs + 200);
            Long ttl = redis.getExpire(SharedCircuitFlag.key(name), java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(ttl).as(name + " 만료(ms)").isBetween(1L, waitMs + 200);
        }
    }

    @Test
    @DisplayName("다른 인스턴스가 연 키가 살아 있으면 승인 · 취소가 Toss 를 부르지 않고 열린 회로와 같은 예외로 끝난다")
    void 키가_살아있으면_승인과_취소가_Toss를_부르지_않는다() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CONFIRM_PATH)).willReturn(aResponse().withStatus(200)));
        openForOthers("toss", 5000);
        openForOthers("toss-cancel", 5000);

        assertThatThrownBy(() -> tossPaymentClient.confirmPayment(request(), "idem")).isInstanceOf(TossCircuitOpenException.class);
        assertThatThrownBy(() -> tossPaymentClient.cancelPayment("pk-x", "사유", "idem")).isInstanceOf(TossCircuitOpenException.class);

        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(CONFIRM_PATH)));
        assertThat(WIRE_MOCK.getAllServeEvents()).as("Toss 로 나간 요청").isEmpty();
        assertThat(circuitBreakerRegistry.circuitBreaker("toss").getState()).as("자기 회로는 키 때문에 열리지 않는다")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("키가 만료되면 호출이 나간다")
    void 키가_만료되면_호출이_나간다() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CONFIRM_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"paymentKey\":\"pk\",\"status\":\"DONE\"}")));
        openForOthers("toss", 300);
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForValue().get(SharedCircuitFlag.key("toss")) == null);

        tossPaymentClient.confirmPayment(request(), "idem");

        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(CONFIRM_PATH)));
    }

    @Test
    @DisplayName("Redis 읽기 · 쓰기가 예외를 던져도 그 이유로 호출이 실패하지 않고 자기 회로만 따른다")
    void Redis가_죽으면_자기_회로만_따른다() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        SharedCircuitFlag flag = new SharedCircuitFlag(broken, circuitBreakerRegistry, List.of("toss"));
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CONFIRM_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"paymentKey\":\"pk\",\"status\":\"DONE\"}")));
        TossPaymentClientImpl client = new TossPaymentClientImpl(restTemplate, cancelRestTemplate, flag);
        ReflectionTestUtils.setField(client, "secretKey", "test_sk_dummy");
        ReflectionTestUtils.setField(client, "apiUrl", WIRE_MOCK.baseUrl());

        assertThat(flag.isOpen("toss")).isFalse();
        assertThatCode(() -> flag.failFastIfOpen("toss")).doesNotThrowAnyException();
        assertThatCode(() -> flag.publishOpen("toss", 1000)).doesNotThrowAnyException();
        assertThatCode(() -> client.confirmPayment(request(), "idem")).doesNotThrowAnyException();

        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(CONFIRM_PATH)));
    }

    @Test
    @DisplayName("보상 배치의 OPEN 이면 쉬는 검사도 키를 따른다")
    void 보상배치는_키가_살아있으면_회차를_쉰다() {
        openForOthers("toss", 60_000);

        compensationService.retryPendingCompensations();

        verify(compensationService, never()).acquireRetryableBatch();

        redis.delete(SharedCircuitFlag.key("toss"));
        compensationService.retryPendingCompensations();

        verify(compensationService, times(1)).acquireRetryableBatch();
    }
}
