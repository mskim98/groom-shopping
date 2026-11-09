package groom.backend.infrastructure.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 토스 취소 응답 3종 분류(429·403·409·ALREADY_CANCELED) 통합 테스트
 *
 * <p>실제 빈(AOP 프록시 - @CircuitBreaker(toss-cancel) + fallbackMethod)을 거쳐 호출하므로
 * cancelPaymentFallback 을 통과해도 분류 예외가 BusinessException 으로 뭉개지지 않는지까지 검증한다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("토스 취소 응답 분류")
class TossPaymentResponseClassificationTest {

    private static final String CANCEL_PATH = "/v1/payments/pk-classify/cancel";

    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("payment.toss.api-url", WIRE_MOCK::baseUrl);
        registry.add("payment.toss.secret-key", () -> "test_sk_dummy");
        // 실행 중인 앱과 Kafka Streams 상태 디렉토리 락이 충돌하지 않도록 테스트에선 Streams 미기동
        registry.add("spring.kafka.streams.auto-startup", () -> "false");
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    // 1M 시드 상품을 부팅 시 Redis 로 복사하는 워밍업 러너를 테스트에선 무력화(OOM·기동시간 방지)
    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private TossPaymentClient tossPaymentClient; // AOP 프록시(@CircuitBreaker 적용)

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetState() {
        WIRE_MOCK.resetAll();
        circuitBreakerRegistry.circuitBreaker("toss-cancel").reset();
    }

    @Test
    @DisplayName("429 는 TossRetryLaterException")
    void cancel_429는_재시도대기_예외() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CANCEL_PATH))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"과다 요청\"}")));

        assertThatThrownBy(() -> tossPaymentClient.cancelPayment("pk-classify", "테스트", "idem-429"))
                .isInstanceOf(TossRetryLaterException.class);
    }

    @Test
    @DisplayName("403 FORBIDDEN_CONSECUTIVE_REQUEST 는 TossRetryLaterException")
    void cancel_403_연속요청거부는_재시도대기_예외() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CANCEL_PATH))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"FORBIDDEN_CONSECUTIVE_REQUEST\",\"message\":\"연속 요청 거부\"}")));

        assertThatThrownBy(() -> tossPaymentClient.cancelPayment("pk-classify", "테스트", "idem-403"))
                .isInstanceOf(TossRetryLaterException.class);
    }

    @Test
    @DisplayName("409 IDEMPOTENT_REQUEST_PROCESSING 는 TossRetryLaterException")
    void cancel_409_처리중은_재시도대기_예외() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CANCEL_PATH))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\",\"message\":\"처리 중\"}")));

        assertThatThrownBy(() -> tossPaymentClient.cancelPayment("pk-classify", "테스트", "idem-409"))
                .isInstanceOf(TossRetryLaterException.class);
    }

    @Test
    @DisplayName("400 ALREADY_CANCELED_PAYMENT 는 TossAlreadyCanceledException")
    void cancel_400_이미취소는_이미취소_예외() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CANCEL_PATH))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"ALREADY_CANCELED_PAYMENT\",\"message\":\"이미 취소됨\"}")));

        assertThatThrownBy(() -> tossPaymentClient.cancelPayment("pk-classify", "테스트", "idem-400"))
                .isInstanceOf(TossAlreadyCanceledException.class);
    }

    @Test
    @DisplayName("404 NOT_FOUND_PAYMENT 는 TossPaymentNotFoundException (승인된 적 없는 결제)")
    void cancel_404_결제없음은_결제없음_예외() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CANCEL_PATH))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제 정보\"}")));

        assertThatThrownBy(() -> tossPaymentClient.cancelPayment("pk-classify", "테스트", "idem-404"))
                .isInstanceOf(TossPaymentNotFoundException.class);
    }
}
