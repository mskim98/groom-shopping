package groom.backend.application.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.github.tomakehurst.wiremock.WireMockServer;
import groom.backend.application.product.ProductStockService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("수동 취소 서킷 경유(T3-6)")
class ManualCancelCircuitTest {

    private static final String CANCEL_PATH = "/v1/payments/.*/cancel";

    // Toss HTTP 호출이 실제로 나갔는지 요청 수로 센다
    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("payment.toss.api-url", WIRE_MOCK::baseUrl);
        registry.add("payment.toss.secret-key", () -> "test_sk_dummy");
        // 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
        registry.add("spring.kafka.streams.auto-startup", () -> "false");
        // 배경 보상 배치가 이 시험의 보상 행을 가져가지 않게 늦춘다
        registry.add("payment.compensation.retry-delay-ms", () -> "600000");
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    // Toss 호출 시점의 트랜잭션 유무를 가로채 기록하는 지점. 실제 메서드는 그대로 실행된다
    @SpyBean
    private TossPaymentClient tossPaymentClient;
    // 취소 후 DB 갱신을 실패시키는 주입 지점(재고 복구는 DB 갱신의 마지막 단계다)
    @SpyBean
    private ProductStockService productStockService;

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
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

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        circuitBreakerRegistry.circuitBreaker("toss-cancel").reset();
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(10);
        payment = fixture.payment(productId, 1);
        String key = "pk-" + UUID.randomUUID();
        payment.ready(key);
        payment.approve(key, "tx-" + key);
        payment = paymentRepository.save(payment);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    private void stubTossCancelOk() {
        WIRE_MOCK.stubFor(post(urlPathMatching(CANCEL_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"paymentKey\":\"" + payment.getPaymentKeyValue() + "\",\"status\":\"CANCELED\"}")));
    }

    @Test
    @DisplayName("toss-cancel 이 OPEN 이면 수동 취소가 즉시 실패하고 Toss HTTP 호출이 없다")
    void 수동취소_toss_cancel_강제OPEN_즉시실패하고_HTTP호출이_없다() {
        circuitBreakerRegistry.circuitBreaker("toss-cancel").transitionToOpenState();

        assertThatThrownBy(() -> paymentApplicationService.cancelPayment(payment.getId(), "단순 변심"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE));

        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlPathMatching(CANCEL_PATH))))
                .as("OPEN 인데 Toss 로 나간 취소 요청").isEmpty();
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
    }

    @Test
    @DisplayName("수동 취소의 Toss 호출 구간에는 활성 트랜잭션이 없다")
    void 수동취소_Toss호출구간에_트랜잭션이_없다() {
        stubTossCancelOk();
        AtomicBoolean txActiveDuringCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            txActiveDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(tossPaymentClient).cancelPayment(anyString(), anyString(), anyString());

        paymentApplicationService.cancelPayment(payment.getId(), "단순 변심");

        assertThat(txActiveDuringCall).as("Toss 취소 호출 중 트랜잭션(커넥션 점유) 활성 여부").isFalse();
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("Toss 취소가 성공한 뒤 DB 갱신이 실패하면 대사용 보상 행이 남고 결제는 DONE 으로 롤백된다")
    void 수동취소_Toss성공뒤_DB갱신실패_보상행이_기록된다() {
        stubTossCancelOk();
        doThrow(new IllegalStateException("db down")).when(productStockService)
                .increaseWithOptimisticLock(any(), anyInt());

        assertThatThrownBy(() -> paymentApplicationService.cancelPayment(payment.getId(), "단순 변심"))
                .isInstanceOf(RuntimeException.class);

        assertThat(fixture.statusOf(payment.getId())).as("DB 롤백 뒤 결제 상태").isEqualTo("DONE");
        // Toss 는 취소됐는데 DB 는 DONE 인 불일치가 조용히 남지 않도록 보상 장부에 기록한다
        assertThat(jdbc.queryForList(
                "SELECT reason FROM payment_compensation WHERE payment_id = ?", String.class, payment.getId()))
                .singleElement().asString().contains("RECONCILE_MANUAL_CANCEL");
    }
}
