package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

// confirm 의 대기가 Redis Pub/Sub 메시지로 깨는지, 메시지를 놓쳐도 키 읽기가 받쳐 주는지, 대기가 끝나면 대기자 등록부가 비는지 본다
// 컨슈머는 꺼 두고 컨슈머가 하는 일(DONE · FAILED 기록과 완료 신호)은 다른 스레드가 SQL 과 신호 기록으로 대신한다
// 폴링 간격을 5초로 둬서, 메시지로 깼다면 간격 안에 끝난다는 사실이 폴링이 아니라 메시지 덕이 되게 한다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=10000",
        "payment.confirm.poll-interval-ms=5000"
})
@DisplayName("결제 확정 대기 - Redis Pub/Sub 로 깸")
class ConfirmPubSubWakeTest {

    private static final long CONSUMER_DELAY_MS = 300;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 비동기 부수효과 차단
    @MockBean
    private TossPaymentClient tossPaymentClient; // 외부 Toss 승인 호출 차단
    @SpyBean
    private ConfirmCompletionSignal signal; // Redis 읽기 장애를 만들려고 가로챈다
    @SpyBean
    private ConfirmWaiters waiters; // 대기자 등록 직전에 컨슈머 대역을 끼워 넣는다

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private MeterRegistry meters;

    private PaymentFixture fixture;
    private UUID productId;
    private Payment payment;
    private String paymentKey;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(10);
        payment = fixture.payment(productId, 1);
        paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
        // 인스턴스의 구독이 서 있어야 메시지가 닿는다 - 컨텍스트가 막 떴을 때의 구독 지연으로 첫 시험이 메시지를 못 받아 실패하지 않게 한다
        await().atMost(Duration.ofSeconds(10)).until(() -> subscribers() > 0);
    }

    @AfterEach
    void tearDown() {
        redis.delete(ConfirmCompletionSignal.keyOf(payment.getId()));
        fixture.cleanup(payment, productId);
    }

    private long subscribers() {
        List<?> reply = redis.execute((org.springframework.data.redis.core.RedisCallback<List<?>>) connection ->
                (List<?>) connection.execute("PUBSUB", "NUMSUB".getBytes(), ConfirmCompletionSignal.CHANNEL.getBytes()));
        return reply == null || reply.size() < 2 ? 0 : ((Number) reply.get(1)).longValue();
    }

    private double wakes(String source) {
        return meters.counter("payment.confirm.wake", "source", source).count();
    }

    private void awaitReady() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!"READY".equals(fixture.statusOf(payment.getId()))) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("READY 가 5초 안에 보이지 않았다");
            }
            sleep(10);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // READY 가 보이면 CONSUMER_DELAY_MS 뒤에 SQL 한 문장으로 상태를 바꾸고 dispatch 로 신호를 낸다. dispatch 가 끝난 시각(nanoTime)을 돌려준다
    private CompletableFuture<Long> consumerAfterReady(String sql, Runnable dispatch, Object... args) {
        return CompletableFuture.supplyAsync(() -> {
            awaitReady();
            sleep(CONSUMER_DELAY_MS);
            jdbc.update(sql, args);
            dispatch.run();
            return System.nanoTime();
        });
    }

    @Test
    @DisplayName("컨슈머가 300ms 뒤 신호를 내면 폴링 간격(5초)이 아니라 메시지로 깨어 1.5초 안에 DONE 을 반환하고 pubsub 으로 센다")
    void 대기_메시지로_깨면_1500ms안에_DONE이고_pubsub으로_센다() throws Exception {
        double before = wakes("pubsub");
        CompletableFuture<Long> signaledAt = consumerAfterReady("UPDATE payment SET status = 'DONE' WHERE id = ?",
                () -> signal.complete(payment.getId()), payment.getId());

        Payment result = ConfirmAsOwner.confirm(paymentApplicationService, payment, paymentKey, PaymentFixture.PRICE);
        long returnedAt = System.nanoTime();

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat((returnedAt - signaledAt.get()) / 1_000_000).as("신호 호출 뒤 반환까지(ms)").isLessThan(1500);
        assertThat(wakes("pubsub") - before).as("pubsub 으로 끝난 대기 수").isEqualTo(1.0);
        assertThat(waiters.size()).as("대기가 끝난 뒤 등록부").isZero();
    }

    @Test
    @DisplayName("신호가 대기자 등록보다 먼저 서도 등록 직후 키를 한 번 읽어 1초 안에 DONE 을 반환하고 key 로 센다")
    void 대기_신호가_등록보다_먼저면_키읽기로_1000ms안에_DONE이고_key로_센다() {
        double before = wakes("key");
        long[] registeredAt = new long[1];
        // 컨슈머가 확정의 대기자 등록보다 먼저 끝낸 경우를 만든다 - 등록 직전에 같은 스레드에서 DONE 기록과 신호를 낸다
        doAnswer(invocation -> {
            registeredAt[0] = System.nanoTime();
            jdbc.update("UPDATE payment SET status = 'DONE' WHERE id = ?", payment.getId());
            signal.complete(payment.getId());
            return invocation.callRealMethod();
        }).when(waiters).register(payment.getId());

        Payment result = ConfirmAsOwner.confirm(paymentApplicationService, payment, paymentKey, PaymentFixture.PRICE);
        long returnedAt = System.nanoTime();

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat((returnedAt - registeredAt[0]) / 1_000_000).as("READY 커밋 뒤 반환까지(ms) - 폴링이면 5000").isLessThan(1000);
        assertThat(wakes("key") - before).as("key 로 끝난 대기 수").isEqualTo(1.0);
        assertThat(waiters.size()).as("대기가 끝난 뒤 등록부").isZero();
    }

    @Test
    @DisplayName("메시지를 놓쳐도(키만 서고 발행 없음) 폴링 간격(500ms) 안에 키를 다시 읽어 1.5초 안에 DONE 을 반환한다")
    void 대기_메시지를_놓치면_다음_키읽기로_1500ms안에_DONE이다() throws Exception {
        ReflectionTestUtils.setField(paymentApplicationService, "confirmPollIntervalMs", 500L);
        try {
            double before = wakes("key");
            CompletableFuture<Long> keySetAt = consumerAfterReady("UPDATE payment SET status = 'DONE' WHERE id = ?",
                    () -> redis.opsForValue().set(ConfirmCompletionSignal.keyOf(payment.getId()),
                            String.valueOf(System.currentTimeMillis()), Duration.ofSeconds(30)),
                    payment.getId());

            Payment result = ConfirmAsOwner.confirm(paymentApplicationService, payment, paymentKey, PaymentFixture.PRICE);
            long returnedAt = System.nanoTime();

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat((returnedAt - keySetAt.get()) / 1_000_000).as("키가 선 뒤 반환까지(ms)").isLessThan(1500);
            assertThat(wakes("key") - before).as("key 로 끝난 대기 수").isEqualTo(1.0);
            assertThat(waiters.size()).as("대기가 끝난 뒤 등록부").isZero();
        } finally {
            ReflectionTestUtils.setField(paymentApplicationService, "confirmPollIntervalMs", 5000L);
        }
    }

    @Test
    @DisplayName("아무도 끝내지 않아 대기 상한에 READY 를 반환해도 등록부가 비고 timeout 으로 센다")
    void 대기_상한에_READY를_반환해도_등록부가_비고_timeout으로_센다() {
        ReflectionTestUtils.setField(paymentApplicationService, "confirmWaitTimeoutMs", 800L);
        ReflectionTestUtils.setField(paymentApplicationService, "confirmPollIntervalMs", 300L);
        try {
            double before = wakes("timeout");

            Payment result = ConfirmAsOwner.confirm(paymentApplicationService, payment, paymentKey, PaymentFixture.PRICE);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(wakes("timeout") - before).as("timeout 으로 끝난 대기 수").isEqualTo(1.0);
            assertThat(waiters.size()).as("대기가 끝난 뒤 등록부").isZero();
        } finally {
            ReflectionTestUtils.setField(paymentApplicationService, "confirmWaitTimeoutMs", 10000L);
            ReflectionTestUtils.setField(paymentApplicationService, "confirmPollIntervalMs", 5000L);
        }
    }

    @Test
    @DisplayName("대기 중 FAILED 가 되면 오류 코드를 보존한 예외를 던지고 등록부가 빈다")
    void 대기_FAILED면_예외를_던지고_등록부가_빈다() {
        consumerAfterReady("UPDATE payment SET status = 'FAILED', failure_code = ?, failure_message = ? WHERE id = ?",
                () -> signal.complete(payment.getId()),
                ErrorCode.INSUFFICIENT_STOCK.name(), "재고가 부족합니다.", payment.getId());

        assertThatThrownBy(() -> ConfirmAsOwner.confirm(paymentApplicationService, payment, paymentKey, PaymentFixture.PRICE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(waiters.size()).as("예외로 끝난 뒤 등록부").isZero();
    }

    @Test
    @DisplayName("Redis 키를 읽지 못해 DB 폴링으로 돌아가 DONE 을 반환해도 등록부가 비고 db 로 센다")
    void 대기_Redis_읽기_실패_DB폴링으로_DONE이어도_등록부가_비고_db로_센다() {
        ReflectionTestUtils.setField(paymentApplicationService, "confirmPollIntervalMs", 100L);
        try {
            double before = wakes("db");
            doThrow(new QueryTimeoutException("시험용 Redis 읽기 장애")).when(signal).completedAt(any());
            consumerAfterReady("UPDATE payment SET status = 'DONE' WHERE id = ?", () -> { }, payment.getId());

            Payment result = ConfirmAsOwner.confirm(paymentApplicationService, payment, paymentKey, PaymentFixture.PRICE);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(wakes("db") - before).as("db 로 끝난 대기 수").isEqualTo(1.0);
            assertThat(waiters.size()).as("대기가 끝난 뒤 등록부").isZero();
        } finally {
            ReflectionTestUtils.setField(paymentApplicationService, "confirmPollIntervalMs", 5000L);
        }
    }
}
