package groom.backend.infrastructure.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

/**
 * ② Resilience4j Circuit Breaker(toss) + ③ HTTP 타임아웃 통합 테스트.
 *
 * <p>WireMock 으로 Toss API 의 지연/5xx 를 주입해 실제 빈(RestTemplate + @CircuitBreaker/@Retry 프록시)이
 * <ul>
 *   <li>③ 응답 타임아웃(5s) 내에 호출을 컷하는지</li>
 *   <li>② 연속 실패 시 CLOSED→OPEN(fast-fail)→HALF_OPEN→CLOSED 로 전이/복구하는지</li>
 * </ul>
 * 를 검증한다. OPEN 유지 시간(prod 30s)은 HALF_OPEN 전이를 빠르게 관찰하기 위해 테스트에서 2s 로만 단축한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Toss 결제 회로차단기/타임아웃 통합 테스트(②③)")
class TossPaymentResilienceIntegrationTest {

    private static final String CONFIRM_PATH = "/v1/payments/confirm";

    // 수동 생명주기 WireMock: static 블록에서 시작해 @DynamicPropertySource 가 포트를 안전하게 참조하도록 한다.
    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("payment.toss.api-url", WIRE_MOCK::baseUrl);
        registry.add("payment.toss.secret-key", () -> "test_sk_dummy");
        // OPEN 유지 시간만 단축(나머지 임계/윈도우는 prod 설정 그대로 사용).
        registry.add("resilience4j.circuitbreaker.instances.toss.wait-duration-in-open-state", () -> "2s");
        // 실행 중인 앱과 Kafka Streams 상태 디렉토리 락이 충돌하지 않도록 테스트에선 Streams 미기동(불필요)
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
    private TossPaymentClient tossPaymentClient; // AOP 프록시(@CircuitBreaker/@Retry 적용)

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetState() {
        WIRE_MOCK.resetAll();
        circuitBreakerRegistry.circuitBreaker("toss").reset();
    }

    @Test
    @DisplayName("③ 응답이 늦으면 responseTimeout(5s) 안에 호출이 컷된다")
    void confirm_요청이_응답타임아웃으로_컷된다() {
        // given - 8초 지연 응답(타임아웃 5s 보다 김)
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CONFIRM_PATH))
                .willReturn(aResponse().withFixedDelay(8000).withStatus(200)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<TossPaymentConfirmRequest> entity =
                new HttpEntity<>(new TossPaymentConfirmRequest("pk", "ord", 1000), headers);

        // when
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> restTemplate.exchange(
                WIRE_MOCK.baseUrl() + CONFIRM_PATH, HttpMethod.POST, entity, String.class))
                .isInstanceOf(Exception.class);
        long elapsed = System.currentTimeMillis() - start;

        // then - 8초를 다 기다리지 않고 5s 부근에서 컷(여유 포함 6.5s 미만)
        assertThat(elapsed).isLessThan(6500);
    }

    @Test
    @DisplayName("② 연속 실패→OPEN(fast-fail), 대기 후 HALF_OPEN 거쳐 성공 시 CLOSED 복구")
    void 회로가_OPEN되고_HALF_OPEN거쳐_CLOSED로_복구된다() throws InterruptedException {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("toss");

        // given - 모든 confirm 호출이 500 으로 실패
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CONFIRM_PATH))
                .willReturn(aResponse().withStatus(500)));

        // when - 회로가 OPEN 되어 fast-fail 이 발생할 때까지 반복 호출
        ErrorCode lastError = null;
        for (int i = 0; i < 15 && cb.getState() != CircuitBreaker.State.OPEN; i++) {
            lastError = callConfirmAndCaptureError();
        }

        // then - 회로 OPEN + 마지막 호출은 회로 OPEN fast-fail(PAYMENT_TEMPORARILY_UNAVAILABLE)
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(lastError).isEqualTo(ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE);

        // when - OPEN 유지 시간(2s) 경과 → 자동으로 HALF_OPEN 으로 전이
        boolean halfOpen = false;
        for (int i = 0; i < 40 && !halfOpen; i++) {
            if (cb.getState() == CircuitBreaker.State.HALF_OPEN) {
                halfOpen = true;
            } else {
                Thread.sleep(100);
            }
        }
        assertThat(halfOpen).as("OPEN 유지 시간 후 HALF_OPEN 전이").isTrue();

        // given - 이제 정상(200) 응답으로 전환
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(post(urlPathEqualTo(CONFIRM_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentKey\":\"pk-test\",\"status\":\"DONE\"}")));

        // when - HALF_OPEN 허용 호출(5건) 모두 성공
        for (int i = 0; i < 5; i++) {
            callConfirmAndCaptureError();
        }

        // then - 회로 CLOSED 복구
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /** confirmPayment 를 호출하고 BusinessException 의 ErrorCode 를 반환(성공 시 null). */
    private ErrorCode callConfirmAndCaptureError() {
        try {
            tossPaymentClient.confirmPayment(
                    new TossPaymentConfirmRequest("pk", "ord", 1000), "idem-key");
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }
}
