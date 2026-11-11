package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import groom.backend.application.cart.CartApplicationService;
import groom.backend.application.order.OrderApplicationService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.infrastructure.resilience.TransactionBulkhead;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

// 장바구니 · 주문 요청이 트랜잭션을 열기 전에 허용 수를 잡는 격벽(TransactionBulkhead)을 본다
// 풀 5 · 허용 4 · 대기 상한 200ms 로 줄여, 풀보다 많은 요청이 한꺼번에 와도 풀 대기 없이 상한 뒤 503 으로 끝나는지 센다
// 컨슈머 · 폴러는 켜 둔다 - 허용을 모두 쥔 동안에도 승인된 확정이 DONE 으로 끝나야 하기 때문이다(P-2)
// 이 클래스의 리스너와 폴러가 클래스 종료 뒤에도 살아 다른 클래스의 outbox 행을 먹지 않게 한다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(TransactionBulkheadTest.Probe.class)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=true",
        "payment.outbox.poll-delay-ms=100",
        "payment.confirm.wait-timeout-ms=10000",
        "payment.confirm.poll-interval-ms=50",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.connection-timeout=1000",
        "resilience4j.bulkhead.instances.transaction.max-concurrent-calls=4",
        "resilience4j.bulkhead.instances.transaction.max-wait-duration=200ms"
})
@DisplayName("트랜잭션 격벽(장바구니 · 주문)")
class TransactionBulkheadTest {

    private static final int PERMITS = 4;
    private static final long BOUND_MS = 200;

    /** 격벽이 붙은 시험용 트랜잭션 메서드 - 안에서 연결을 쥐고 있는 트랜잭션 수를 센다 */
    @Component
    static class Probe {
        private final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger maxOpen = new AtomicInteger();
        @Autowired
        private JdbcTemplate jdbc;

        // 스프링 프록시 너머라 필드를 직접 읽으면 null 이다 - 메서드로 읽는다
        public int open() {
            return open.get();
        }

        public int maxOpen() {
            return maxOpen.get();
        }

        public void reset() {
            open.set(0);
            maxOpen.set(0);
        }

        private void enter() {
            maxOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
        }

        @TransactionBulkhead
        @Transactional
        public void sleepInTx(double seconds) {
            enter();
            try {
                jdbc.query("SELECT 1 FROM pg_sleep(?)", rs -> null, seconds);
            } finally {
                open.decrementAndGet();
            }
        }

        @TransactionBulkhead
        @Transactional
        public void failInTx() {
            enter();
            try {
                jdbc.query("SELECT 1", rs -> null);
                throw new IllegalStateException("시험용 실패");
            } finally {
                open.decrementAndGet();
            }
        }

        @TransactionBulkhead
        @Transactional
        public void holdInTx(CountDownLatch release) throws InterruptedException {
            enter();
            try {
                jdbc.query("SELECT 1", rs -> null);
                release.await(30, TimeUnit.SECONDS);
            } finally {
                open.decrementAndGet();
            }
        }
    }

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 비동기 부수효과 차단
    @MockBean
    private TossPaymentClient tossPaymentClient; // 외부 Toss 승인 호출 차단

    @Autowired
    private Probe probe;
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

    private PaymentFixture fixture;

    @BeforeEach
    void setUp() {
        probe.reset();
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
    }

    /** 한 호출의 결과: 성공이면 error 가 null, elapsedMs 는 호출이 끝나기까지 */
    private record Outcome(Throwable error, long elapsedMs) {
    }

    private List<Outcome> burst(int callers, ThrowingRunnable call) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    long start = System.nanoTime();
                    Throwable error = null;
                    try {
                        call.run();
                    } catch (Throwable e) {
                        error = e;
                    }
                    return new Outcome(error, (System.nanoTime() - start) / 1_000_000);
                }));
            }
            gate.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static boolean causedByConnectionTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getClass().getName().contains("SQLTransientConnectionException")
                    || String.valueOf(t.getMessage()).contains("Connection is not available")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBulkheadRefusal(Throwable error) {
        return error instanceof BusinessException business
                && business.getErrorCode() == ErrorCode.TRANSACTION_BULKHEAD_FULL;
    }

    @Test
    @DisplayName("풀 5 · 허용 4 · 호출자 50 이 동시에 오면 풀 대기 없이 동시 트랜잭션은 4 이하이고 넘은 호출은 상한 뒤 503 이다")
    void 격벽_동시50_허용4_풀대기없이_넘은호출은_상한뒤_503() throws Exception {
        List<Outcome> outcomes = burst(50, () -> probe.sleepInTx(0.3));

        assertThat(outcomes).as("연결 풀 대기 시간 초과가 하나도 없다").noneMatch(o -> causedByConnectionTimeout(o.error()));
        assertThat(outcomes).as("성공도 거절도 아닌 다른 실패가 없다")
                .allMatch(o -> o.error() == null || isBulkheadRefusal(o.error()));
        assertThat(probe.maxOpen()).as("동시에 열린 트랜잭션 최대 수").isLessThanOrEqualTo(PERMITS);
        assertThat(outcomes.stream().filter(o -> o.error() == null).count()).as("허용을 잡은 호출은 성공한다").isGreaterThanOrEqualTo(PERMITS);
        List<Outcome> refused = outcomes.stream().filter(o -> o.error() != null).toList();
        assertThat(refused).as("호출자 50 에 허용 4 라 거절이 있다").isNotEmpty();
        assertThat(refused).as("거절은 상한 + 300ms 안에 돌아온다").allMatch(o -> o.elapsedMs() <= BOUND_MS + 300);
        assertThat(ErrorCode.TRANSACTION_BULKHEAD_FULL.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // 폭주가 끝난 뒤 허용이 모두 돌아와 있어 호출이 성공한다
        probe.sleepInTx(0.01);
        List<Outcome> afterBurst = burst(PERMITS, () -> probe.sleepInTx(0.05));
        assertThat(afterBurst).as("허용 수만큼 동시에 와도 모두 성공한다").allMatch(o -> o.error() == null);
    }

    @Test
    @DisplayName("트랜잭션이 예외로 롤백돼도 허용을 돌려준다")
    void 격벽_롤백_예외뒤에도_허용을_돌려준다() throws Exception {
        for (int i = 0; i < PERMITS * 3; i++) {
            try {
                probe.failInTx();
            } catch (IllegalStateException expected) {
                // 허용을 쥔 채 남지 않아야 한다
            }
        }

        List<Outcome> outcomes = burst(PERMITS, () -> probe.sleepInTx(0.05));

        assertThat(outcomes).as("롤백만 12번 한 뒤에도 허용 4 가 모두 남아 있다").allMatch(o -> o.error() == null);
    }

    @Test
    @DisplayName("장바구니 · 주문 요청 진입점에는 격벽이 붙고, 결제 흐름에서 더는 부르지 않는 clearOrderedItems 에는 붙지 않는다")
    void 격벽_진입점에만_붙고_후처리용_메서드에는_안붙는다() throws Exception {
        for (String name : List.of("addToCart", "getCartItems", "removeCartItems", "increaseQuantity", "decreaseQuantity")) {
            assertThat(methodNamed(CartApplicationService.class, name).isAnnotationPresent(TransactionBulkhead.class))
                    .as("CartApplicationService." + name).isTrue();
        }
        for (String name : List.of("createOrder", "getOrderById")) {
            assertThat(methodNamed(OrderApplicationService.class, name).isAnnotationPresent(TransactionBulkhead.class))
                    .as("OrderApplicationService." + name).isTrue();
        }
        assertThat(methodNamed(CartApplicationService.class, "clearOrderedItems").isAnnotationPresent(TransactionBulkhead.class))
                .as("결제 흐름에서 부르지 않는 메서드이고, 나중에 별도 API 가 불러도 진입점이 아닌 쪽에 둔다").isFalse();
    }

    private static Method methodNamed(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + "." + name + " 가 없다"));
    }

    @Test
    @DisplayName("허용을 모두 쥔 장바구니 트랜잭션이 있어도 Toss 승인이 끝난 확정은 READY · outbox 를 남기고 컨슈머를 거쳐 DONE 이 된다")
    void 격벽_허용을_모두_쥔_동안에도_승인된_confirm은_DONE으로_끝난다() throws Exception {
        UUID productId = fixture.product(10);
        Payment payment = fixture.payment(productId, 1);
        String paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService holders = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < PERMITS; i++) {
                    holders.submit(() -> {
                        probe.holdInTx(release);
                        return null;
                    });
                }
                await().atMost(5, TimeUnit.SECONDS).until(() -> probe.open() == PERMITS);

                // 허용이 모두 차 있다는 증거 - 같은 격벽의 다음 호출은 거절된다
                Outcome extra = burst(1, () -> probe.sleepInTx(0.01)).get(0);
                assertThat(isBulkheadRefusal(extra.error())).as("허용 4 를 다 쥔 동안의 다섯 번째 호출").isTrue();

                Payment result = ConfirmAsOwner.confirm(paymentApplicationService, payment,
                        paymentKey, PaymentFixture.PRICE);

                assertThat(result.getStatus()).as("컨슈머가 후처리를 끝내 DONE 을 돌려받는다").isEqualTo(PaymentStatus.DONE);
                assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE aggregate_id = ?",
                        Integer.class, payment.getId())).as("READY 와 함께 남은 outbox 행").isEqualTo(1);
            } finally {
                release.countDown();
                fixture.cleanup(payment, productId);
            }
        }
    }

    @AfterEach
    void tearDown() {
        probe.reset();
    }
}
