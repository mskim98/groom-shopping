package groom.backend.application.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Task 6 - GIVEN_UP 재구동 런북(runbook/compensation_redrive.sql) 통합 테스트.
 *
 * <p>런북 SQL 파일을 직접 읽어 {@link JdbcTemplate}로 실행한다. 시험용 SQL 을 따로 적으면
 * 런북과 시험이 갈리므로, 같은 파일을 실행 대상으로 삼는다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("보상 GIVEN_UP 재구동 런북 통합 테스트(Task 6)")
class PaymentCompensationRedriveIntegrationTest {

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
        // 배경 스케줄러(fixedDelay 30s)가 테스트 도중 끼어들어 수동 호출과 경합하지 않도록 충분히 늦춘다
        registry.add("payment.compensation.retry-delay-ms", () -> "600000");
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    // 1M 시드 상품을 부팅 시 Redis 로 복사하는 워밍업 러너를 테스트에선 무력화(OOM·기동시간 방지)
    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PaymentCompensationService compensationService;
    @Autowired
    private PaymentCompensationRepository compensationRepository;

    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    @Test
    @DisplayName("런북 실행 후 배치가 같은 멱등키로 GIVEN_UP 행만 재시도해 SUCCEEDED 로 만들고, 대상 밖 행은 건드리지 않는다")
    void redrive_reopensGivenUpRow_andLeavesOthersUntouched() throws IOException {
        // given - GIVEN_UP 으로 종결된 보상 1건
        String paymentKey = "pk-redrive-" + UUID.randomUUID();
        UUID paymentId = insertPaymentRow();
        PaymentCompensation givenUp = compensationRepository.save(PaymentCompensation.builder()
                .paymentId(paymentId)
                .paymentKey(paymentKey)
                .amount(1000)
                .reason("DB 후처리 실패")
                .maxRetryCount(1)
                .build());
        givenUp.markFailed("toss down");
        givenUp = compensationRepository.save(givenUp);
        assertThat(givenUp.getStatus()).isEqualTo(CompensationStatus.GIVEN_UP);

        // GIVEN_UP 이 아닌 행 - 런북 대상 id 목록에 함께 포함시켜, 이 행이 안전한 이유가
        // "id 목록에서 뺐기 때문" 이 아니라 "런북 WHERE 의 status 조건" 임을 검증한다
        UUID otherPaymentId = insertPaymentRow();
        String untouchedPaymentKey = "pk-untouched-" + UUID.randomUUID();
        PaymentCompensation succeeded = compensationRepository.save(PaymentCompensation.builder()
                .paymentId(otherPaymentId)
                .paymentKey(untouchedPaymentKey)
                .amount(2000)
                .reason("DB 후처리 실패")
                .maxRetryCount(5)
                .build());
        succeeded.markSucceeded();
        succeeded = compensationRepository.save(succeeded);

        String expectedIdempotencyKey = paymentKey + ":compensate:" + givenUp.getId();

        WIRE_MOCK.stubFor(post(urlPathMatching("/v1/payments/.+/cancel"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentKey\":\"" + paymentKey + "\",\"status\":\"CANCELED\"}")));

        // when - 런북 SQL 을 그대로 읽어 대상 id 두 건(GIVEN_UP·SUCCEEDED)을 모두 넣고 실행
        String sql = loadRunbookSql()
                .replace(":ids", "'" + givenUp.getId() + "','" + succeeded.getId() + "'");
        jdbcTemplate.update(sql);

        compensationService.retryPendingCompensations();

        // then - GIVEN_UP 이던 행은 SUCCEEDED, 취소 요청은 GIVEN_UP 이전과 같은 멱등키로 나감
        PaymentCompensation reloaded = compensationRepository.findById(givenUp.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);

        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo("/v1/payments/" + paymentKey + "/cancel"))
                .withHeader("Idempotency-Key", equalTo(expectedIdempotencyKey)));

        // then - SUCCEEDED 였던 행은 id 목록에 있었는데도 런북이 건드리지 않는다
        PaymentCompensation untouched = compensationRepository.findById(succeeded.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(CompensationStatus.SUCCEEDED);
        assertThat(untouched.getRetryCount()).isEqualTo(0);

        // 상태값만으로는 "재처리됐지만 우연히 같은 결과에 도달" 을 가려내지 못한다(취소 API 가 항상
        // 성공 응답을 주는 스텁이라, 대상 밖 행이 재처리돼도 종국엔 같은 SUCCEEDED/retryCount=0 에
        // 도달할 수 있다). 그 행의 결제키로 취소 요청 자체가 아예 없었는지를 직접 확인해야
        // "런북 WHERE 의 status 조건이 막았다" 는 것을 증명한다
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo("/v1/payments/" + untouchedPaymentKey + "/cancel")));
    }

    private String loadRunbookSql() throws IOException {
        Path path = Path.of("runbook/compensation_redrive.sql");
        if (!Files.exists(path)) {
            path = Path.of("backend/runbook/compensation_redrive.sql");
        }
        return Files.readString(path);
    }

    /** users → orders → payment 최소 행을 만들어 payment_compensation 의 FK 를 충족시킨다 */
    private UUID insertPaymentRow() {
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password, name) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                "r" + UUID.randomUUID().toString().substring(0, 8) + "@t.local", "pw", "재구동 시험 사용자");

        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO orders (id, user_id, sub_total, total_amount) VALUES (?, ?, ?, ?)",
                orderId, userId, 1000, 1000);

        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payment (id, order_id, user_id, amount) VALUES (?, ?, ?, ?)",
                paymentId, orderId, userId, 1000);

        return paymentId;
    }
}
