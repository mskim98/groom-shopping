package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.domain.payment.repository.PaymentRepository;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// confirm 의 대기가 폴링마다 DB 연결을 빌리지 않는지 본다(r120 Diagnosis 2 B3)
// 컨슈머는 꺼 두고, 다른 스레드가 진짜 후처리(applyApprovedEvent)를 정해진 시간 뒤에 부른다 - 승인 트랜잭션이 커밋된 뒤 Redis 완료 신호가 서야 한다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=3000",
        "payment.confirm.poll-interval-ms=100"
})
@DisplayName("결제 확정 대기 - Redis 완료 신호")
class ConfirmWaitSignalTest {

    private static final long WAIT_TIMEOUT_MS = 3000;
    private static final long CONSUMER_HOLD_MS = 1500;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 비동기 부수효과 차단
    @MockBean
    private TossPaymentClient tossPaymentClient; // 외부 Toss 승인 호출 차단
    @SpyBean
    private ConfirmCompletionSignal signal; // Redis 장애 · 신호 유실을 만들려고 가로챈다
    @SpyBean
    private PaymentRepository paymentRepositorySpy; // 확정을 부른 스레드가 결제를 몇 번 읽는지 센다

    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    private PaymentFixture fixture;
    private UUID productId;
    private Payment payment;
    private String paymentKey;
    private PaymentApprovedEvent event;
    private final AtomicInteger readsByConfirm = new AtomicInteger();

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(10);
        payment = fixture.payment(productId, 1);
        paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
        event = PaymentFixture.approvedEvent(payment, productId, 1, paymentKey);
        readsByConfirm.set(0);
    }

    @AfterEach
    void tearDown() {
        redis.delete(ConfirmCompletionSignal.keyOf(payment.getId()));
        fixture.cleanup(payment, productId);
    }

    // 확정을 부르는 이 스레드의 findById 만 센다 - 컨슈머 스레드가 읽는 것은 세지 않는다
    private void countFindByIdCalledBy(Thread confirmThread) {
        doAnswer(invocation -> {
            if (Thread.currentThread() == confirmThread) {
                readsByConfirm.incrementAndGet();
            }
            return invocation.callRealMethod();
        }).when(paymentRepositorySpy).findById(any());
    }

    // READY 가 보이면 holdMs 뒤에 진짜 후처리를 부른다. READY 가 보인 시각(nanoTime)을 돌려준다
    private CompletableFuture<Long> consumerAfter(long holdMs) {
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!"READY".equals(fixture.statusOf(payment.getId()))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("READY 가 5초 안에 보이지 않았다");
                }
                sleep(10);
            }
            long readyAt = System.nanoTime();
            sleep(holdMs);
            paymentApplicationService.applyApprovedEvent(event);
            return readyAt;
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("컨슈머가 1.5초 붙들어도 신호를 기다리는 확정은 결제를 DB 에서 2번 이하로 읽고 1.9초 안에 DONE 을 반환한다")
    void 신호_컨슈머_1500ms_보류_DB읽기_2번이하_1900ms안에_DONE() throws Exception {
        countFindByIdCalledBy(Thread.currentThread());
        CompletableFuture<Long> readyAt = consumerAfter(CONSUMER_HOLD_MS);

        Payment result = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);
        long returnedAt = System.nanoTime();

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.DONE);
        long sinceReadyMs = (returnedAt - readyAt.get(5, TimeUnit.SECONDS)) / 1_000_000;
        assertThat(sinceReadyMs).as("READY 가 보인 뒤 반환까지(ms)").isBetween(CONSUMER_HOLD_MS - 50, CONSUMER_HOLD_MS + 400);
        assertThat(readsByConfirm.get()).as("확정 한 번이 결제를 DB 에서 읽은 횟수(READY 기록 트랜잭션 1 + 대기 끝 1)").isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Redis 를 읽지 못하면 기존 DB 폴링으로 돌아가 DONE 을 반환한다")
    void 신호_Redis_장애_DB폴링으로_돌아가_DONE() throws Exception {
        doThrow(new RedisConnectionFailureException("시험용 Redis 장애")).when(signal).completedAt(any());
        countFindByIdCalledBy(Thread.currentThread());
        CompletableFuture<Long> readyAt = consumerAfter(500);

        Payment result = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat((System.nanoTime() - readyAt.get(5, TimeUnit.SECONDS)) / 1_000_000)
                .as("폴링 간격(100ms) 안에 DONE 을 본다").isLessThan(500 + 400);
        assertThat(readsByConfirm.get()).as("폴링으로 돌아갔으니 DB 읽기가 신호 경로(2)보다 많다").isGreaterThan(2);
    }

    @Test
    @DisplayName("컨슈머가 끝내지 않으면 대기 상한 뒤 READY 를 반환한다")
    void 신호_컨슈머가_끝내지_않으면_상한뒤_READY() {
        long start = System.nanoTime();

        Payment result = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat((System.nanoTime() - start) / 1_000_000).as("대기 상한만큼 기다린 뒤 돌아온다").isGreaterThanOrEqualTo(WAIT_TIMEOUT_MS - 50);
    }

    @Test
    @DisplayName("승인은 커밋됐는데 신호 기록이 실패하면 대기 상한에 마지막 DB 읽기로 DONE 을 반환한다(202 가 아니다)")
    void 신호_유실_승인커밋뒤_신호가_없어도_상한에_DONE을_반환한다() throws Exception {
        doNothing().when(signal).complete(any());
        CompletableFuture<Long> readyAt = consumerAfter(300);

        long start = System.nanoTime();
        Payment result = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        readyAt.get(5, TimeUnit.SECONDS);

        assertThat(result.getStatus()).as("마지막 DB 읽기가 DONE 을 본다").isEqualTo(PaymentStatus.DONE);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(elapsedMs).as("신호가 없으면 상한까지 기다린다 - 잘못된 답이 아니라 늦은 답이다").isGreaterThanOrEqualTo(WAIT_TIMEOUT_MS - 50);
    }

    @Test
    @DisplayName("신호의 수명은 대기 상한보다 길다")
    void 신호_수명은_대기상한보다_길다() {
        signal.complete(payment.getId());

        Long ttlMs = redis.getExpire(ConfirmCompletionSignal.keyOf(payment.getId()), TimeUnit.MILLISECONDS);

        assertThat(ttlMs).as("남은 수명(ms)").isGreaterThan(WAIT_TIMEOUT_MS);
        assertThat(signal.completedAt(payment.getId())).as("기록한 신호는 읽힌다").isNotNull();
    }
}
