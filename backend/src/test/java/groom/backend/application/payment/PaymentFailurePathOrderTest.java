package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.cart.CartApplicationService;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockDecrementer;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=1000",
        "payment.confirm.poll-interval-ms=50",
        // 배경 보상 배치가 이 시험의 보상 행을 가져가지 않게 늦춘다
        "payment.compensation.retry-delay-ms=600000"})
@DisplayName("결제 실패 경로 순서와 커밋 뒤 부수효과(T3-5)")
class PaymentFailurePathOrderTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private ProductStockRedisRepository productStockRedisRepository; // 선점 성공 · 해제 실패를 주입하는 지점
    @MockBean
    private TossPaymentClient tossPaymentClient; // 승인 성공 응답을 주입하는 지점
    @SpyBean
    private StockDecrementer stockDecrementer; // 실제 차감 뒤에 후처리를 실패시키는 지점(Outbox 적재는 확정 쪽으로 옮겨져 후처리에 없다)
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 커밋 뒤 호출 여부를 확인하는 지점
    @SpyBean
    private PaymentCompensationService compensationService; // 실제 기록은 하되 호출 순서를 확인한다

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
    private ObjectMapper objectMapper;

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
        given(productStockRedisRepository.preReserve(any(), anyInt())).willReturn(1L);
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willReturn(PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    // 실제 차감을 한 뒤 던져 후처리 트랜잭션이 재고 차감까지 롤백하게 한다
    private void failPostProcessAfterDecrease(RuntimeException failure) {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw failure;
        }).when(stockDecrementer).decrease(any(), anyInt());
    }

    private void failPostProcessAndRedisRelease() {
        failPostProcessAfterDecrease(new IllegalStateException("post-process down"));
        doThrow(new IllegalStateException("redis down")).when(productStockRedisRepository).release(any(), anyInt());
    }

    // Outbox 에 적재된 PaymentApproved 이벤트를 컨슈머 대신 서비스 진입점에 먹인다
    private void driveConsumer() throws Exception {
        String payload = jdbc.queryForObject(
                "SELECT payload FROM payment_outbox WHERE aggregate_id = ?", String.class, payment.getId());
        paymentApplicationService.applyApprovedEvent(objectMapper.readValue(payload, PaymentApprovedEvent.class));
    }

    @Test
    @DisplayName("후처리 실패 뒤 Redis 선점 해제가 예외여도 보상 의도가 기록되고 결제는 FAILED 가 된다")
    void 실패경로_Redis해제가_예외여도_보상의도가_기록된다() throws Exception {
        failPostProcessAndRedisRelease();

        // 확정은 READY 로 돌아오고(컨슈머는 꺼 둠) 후처리는 이벤트를 받은 서비스 진입점이 한다
        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000);
        driveConsumer();

        assertThat(jdbc.queryForList("SELECT status FROM payment_compensation WHERE payment_id = ?",
                String.class, payment.getId())).as("보상 의도 행").hasSize(1);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("후처리 실패 뒤 보상 의도 기록이 Redis 선점 해제보다 먼저 일어난다")
    void 실패경로_보상의도가_Redis해제보다_먼저_기록된다() throws Exception {
        failPostProcessAndRedisRelease();

        paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000);
        driveConsumer();

        InOrder order = inOrder(compensationService, productStockRedisRepository);
        order.verify(compensationService).compensate(eq(payment.getId()), eq(paymentKey), eq(1000), anyString());
        order.verify(productStockRedisRepository).release(eq(productId), eq(1));
    }

    @Test
    @DisplayName("후처리가 롤백되면 장바구니 비우기와 재고 알림이 호출되지 않는다")
    void 후처리롤백_장바구니비우기와_알림이_호출되지_않는다() {
        // 메서드 본문은 끝까지 성공하고 커밋 직전에 실패한다 - 본문 안에서 부른 부수효과는 이미 나간 뒤라 롤백을 못 따른다
        doAnswer(invocation -> {
            Object decreased = invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("commit failed");
                }
            });
            return decreased;
        }).when(stockDecrementer).decrease(any(), anyInt());

        assertThatThrownBy(() -> paymentApplicationService.applyApprovedPaymentInTx(
                payment.getOrderId(), PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE)))
                .hasMessageContaining("commit failed");
        assertThat(fixture.statusOf(payment.getId())).as("롤백 뒤 결제 상태").isNotEqualTo("DONE");

        verify(paymentNotificationService, never()).clearCartItems(any(), any(), any());
        verify(paymentNotificationService, never()).sendStockReducedNotifications(any(), any());
    }

    @Test
    @DisplayName("후처리가 커밋된 뒤에야 장바구니 비우기와 재고 알림이 호출된다")
    void 후처리커밋_장바구니비우기와_알림이_커밋뒤_호출된다() {
        // 호출 시점에 다른 커넥션으로 본 결제 상태 - 커밋 전이면 PENDING 이다
        AtomicReference<String> statusAtCartClear = new AtomicReference<>();
        AtomicReference<String> statusAtNotification = new AtomicReference<>();
        doAnswer(invocation -> {
            statusAtCartClear.set(fixture.statusOf(payment.getId()));
            return null;
        }).when(paymentNotificationService).clearCartItems(any(), any(), any());
        doAnswer(invocation -> {
            statusAtNotification.set(fixture.statusOf(payment.getId()));
            return null;
        }).when(paymentNotificationService).sendStockReducedNotifications(any(), any());

        paymentApplicationService.applyApprovedPaymentInTx(
                payment.getOrderId(), PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));

        assertThat(statusAtCartClear.get()).as("장바구니 비우기 호출 시점의 DB 결제 상태").isEqualTo("DONE");
        assertThat(statusAtNotification.get()).as("재고 알림 호출 시점의 DB 결제 상태").isEqualTo("DONE");
    }

    @Test
    @DisplayName("Toss 확정이 시간 초과로 끝나면 승인 여부를 알 수 없어 보상 의도를 기록하고 결제는 FAILED 가 된다")
    void 확정시간초과_승인여부불명_보상의도를_기록한다() {
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_PG_TIMEOUT));

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000))
                .isInstanceOf(BusinessException.class);

        assertThat(jdbc.queryForList("SELECT payment_key FROM payment_compensation WHERE payment_id = ?",
                String.class, payment.getId())).as("시간 초과 보상 행의 paymentKey").containsExactly(paymentKey);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Toss 가 4xx 로 거절한 확정은 승인된 적이 없으므로 보상 의도를 기록하지 않는다")
    void 확정카드거절_승인된적없음_보상의도를_기록하지_않는다() {
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_PG_REJECTED));

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000))
                .isInstanceOf(BusinessException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_compensation WHERE payment_id = ?",
                Long.class, payment.getId())).isZero();
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Toss 확정 시간 초과 실패에서도 보상 의도 기록이 Redis 선점 해제보다 먼저 일어난다")
    void 확정시간초과_보상의도가_Redis해제보다_먼저_기록된다() {
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_PG_TIMEOUT));

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000))
                .isInstanceOf(BusinessException.class);

        InOrder order = inOrder(compensationService, productStockRedisRepository);
        order.verify(compensationService).compensate(eq(payment.getId()), eq(paymentKey), eq(1000), anyString());
        order.verify(productStockRedisRepository).release(eq(productId), eq(1));
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("커밋 뒤 호출에는 엔티티가 아니라 트랜잭션 안에서 만든 평범한 값(사용자 id · 주문 id · 항목)이 전달된다")
    void 후처리커밋_커밋뒤_호출은_엔티티가_아닌_평범한_값을_받는다() {
        // 커밋 뒤에는 트랜잭션이 없다(open-in-view 도 꺼져 있다) - 받은 값만으로 읽을 수 있어야 한다
        AtomicReference<Long> cartUserId = new AtomicReference<>();
        AtomicReference<UUID> cartOrderId = new AtomicReference<>();
        AtomicReference<List<CartApplicationService.CartItemToRemove>> cartItems = new AtomicReference<>();
        AtomicReference<Long> notifyUserId = new AtomicReference<>();
        AtomicReference<Integer> stockAfter = new AtomicReference<>();
        doAnswer(invocation -> {
            cartUserId.set(invocation.getArgument(0));
            cartOrderId.set(invocation.getArgument(1));
            cartItems.set(invocation.getArgument(2));
            return null;
        }).when(paymentNotificationService).clearCartItems(any(), any(), any());
        doAnswer(invocation -> {
            List<PaymentNotificationService.StockReductionResult> reductions = invocation.getArgument(0);
            stockAfter.set(reductions.get(0).getStockAfter());
            notifyUserId.set(invocation.getArgument(1));
            return null;
        }).when(paymentNotificationService).sendStockReducedNotifications(any(), any());

        paymentApplicationService.applyApprovedPaymentInTx(
                payment.getOrderId(), PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));

        assertThat(cartUserId.get()).isEqualTo(1L);
        assertThat(cartOrderId.get()).isEqualTo(payment.getOrderId());
        assertThat(cartItems.get()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(productId);
            assertThat(item.getQuantity()).isEqualTo(1);
        });
        assertThat(notifyUserId.get()).isEqualTo(1L);
        assertThat(stockAfter.get()).as("알림에 실리는 차감 후 재고").isEqualTo(9);
    }

    @Test
    @DisplayName("후처리가 매 시도마다 version 충돌이면 대기 중인 확정 요청이 409(PRODUCT_STOCK_CONFLICT)로 답하고 보상 의도가 기록되며 재고는 그대로다")
    void 후처리_매시도_version충돌_409로_답하고_보상의도를_기록한다() throws Exception {
        // 재고 차감 뒤 매번 낙관적 락 충돌이 난다 - 시도마다 롤백되므로 재고는 그대로여야 한다
        failPostProcessAfterDecrease(new ObjectOptimisticLockingFailureException(Payment.class, payment.getId()));

        // 확정 요청은 READY 가 된 뒤 컨슈머의 결과를 기다린다. 그 사이 후처리를 돌려 FAILED 를 만든다
        CompletableFuture<Payment> confirm = CompletableFuture.supplyAsync(() ->
                paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000));
        for (int i = 0; i < 100 && !"READY".equals(fixture.statusOf(payment.getId())); i++) {
            Thread.sleep(20);
        }
        driveConsumer();

        assertThatThrownBy(() -> confirm.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(BusinessException.class)
                .cause().isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_STOCK_CONFLICT));

        verify(stockDecrementer, times(3)).decrease(any(), anyInt());
        assertThat(jdbc.queryForList("SELECT payment_key FROM payment_compensation WHERE payment_id = ?",
                String.class, payment.getId())).as("이미 승인된 결제의 환불 의도").containsExactly(paymentKey);
        assertThat(fixture.stockOf(productId)).isEqualTo(10);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }
}
