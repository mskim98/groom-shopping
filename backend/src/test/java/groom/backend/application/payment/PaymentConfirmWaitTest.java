package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// confirm 이 Outbox 적재 뒤 Payment 상태를 다시 읽으며 DONE · FAILED 를 기다리는 동작을 본다
// 컨슈머는 꺼 두고, 컨슈머가 하는 일(DONE · FAILED 기록)은 다른 스레드가 SQL 로 대신한다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=1000",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("결제 확정 결과 대기")
class PaymentConfirmWaitTest {

    private static final long WAIT_TIMEOUT_MS = 1000;
    private static final long FLIP_AFTER_MS = 300;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 비동기 부수효과 차단
    @MockBean
    private TossPaymentClient tossPaymentClient; // 외부 Toss 승인 호출 차단

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
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    // READY 가 보이면(= 확정 쪽 트랜잭션이 커밋되면) FLIP_AFTER_MS 뒤에 SQL 한 문장으로 상태를 바꾼다
    // 읽은 시각을 돌려줘 호출이 그로부터 얼마 만에 끝났는지 잴 수 있게 한다
    private CompletableFuture<Long> flipWhenReady(String sql, Object... args) {
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!"READY".equals(fixture.statusOf(payment.getId()))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("READY 가 5초 안에 보이지 않았다");
                }
                sleep(10);
            }
            long readyAt = System.nanoTime();
            sleep(FLIP_AFTER_MS);
            jdbc.update(sql, args);
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
    @DisplayName("다른 스레드가 300ms 뒤 DONE 으로 바꾸면 대기 상한(1초)보다 먼저 DONE 을 반환한다")
    void 대기_다른스레드가_300ms뒤_DONE으로_바꾸면_1초안에_DONE을_반환한다() throws Exception {
        CompletableFuture<Long> readyAt = flipWhenReady("UPDATE payment SET status = 'DONE' WHERE id = ?", payment.getId());

        Payment result = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);
        long returnedAt = System.nanoTime();

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.DONE);
        long sinceReadyMs = (returnedAt - readyAt.get()) / 1_000_000;
        assertThat(sinceReadyMs).as("READY 가 보인 뒤 반환까지(ms) - 상한까지 기다렸다면 1000 이상").isLessThan(WAIT_TIMEOUT_MS - 100);
    }

    @Test
    @DisplayName("아무도 상태를 바꾸지 않으면 대기 상한이 지난 뒤 READY 를 반환한다")
    void 대기_아무도_바꾸지_않으면_상한뒤_READY를_반환한다() {
        long start = System.nanoTime();

        Payment result = paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), PaymentFixture.PRICE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(elapsedMs).as("대기 상한(ms)만큼은 기다린 뒤 돌아온다").isGreaterThanOrEqualTo(WAIT_TIMEOUT_MS - 50);
    }

    @Test
    @DisplayName("대기 중 FAILED 로 바뀌면 컨슈머가 기록한 오류 코드를 보존한 BusinessException 을 던진다")
    void 대기_FAILED로_바뀌면_오류코드를_보존한_예외를_던진다() {
        flipWhenReady("UPDATE payment SET status = 'FAILED', failure_code = ?, failure_message = ? WHERE id = ?",
                ErrorCode.INSUFFICIENT_STOCK.name(), "재고가 부족합니다.", payment.getId());

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(
                paymentKey, payment.getOrderId(), PaymentFixture.PRICE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }
}
