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
import static org.mockito.Mockito.verify;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
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
    @MockBean
    private PaymentOutboxRepository outboxRepository; // 재고 차감 뒤 후처리를 실패시키는 지점
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

    private void failPostProcessAndRedisRelease() {
        doThrow(new IllegalStateException("outbox down")).when(outboxRepository).save(any());
        doThrow(new IllegalStateException("redis down")).when(productStockRedisRepository).release(any(), anyInt());
    }

    @Test
    @DisplayName("후처리 실패 뒤 Redis 선점 해제가 예외여도 보상 의도가 기록되고 결제는 FAILED 가 된다")
    void 실패경로_Redis해제가_예외여도_보상의도가_기록된다() {
        failPostProcessAndRedisRelease();

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000))
                .hasMessageContaining("outbox down");

        assertThat(jdbc.queryForList("SELECT status FROM payment_compensation WHERE payment_id = ?",
                String.class, payment.getId())).as("보상 의도 행").hasSize(1);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("후처리 실패 뒤 보상 의도 기록이 Redis 선점 해제보다 먼저 일어난다")
    void 실패경로_보상의도가_Redis해제보다_먼저_기록된다() {
        failPostProcessAndRedisRelease();

        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000))
                .isInstanceOf(RuntimeException.class);

        InOrder order = inOrder(compensationService, productStockRedisRepository);
        order.verify(compensationService).compensate(eq(payment.getId()), eq(paymentKey), eq(1000), anyString());
        order.verify(productStockRedisRepository).release(eq(productId), eq(1));
    }

    @Test
    @DisplayName("후처리가 롤백되면 장바구니 비우기와 재고 알림이 호출되지 않는다")
    void 후처리롤백_장바구니비우기와_알림이_호출되지_않는다() {
        // 메서드 본문은 끝까지 성공하고 커밋 직전에 실패한다 - 본문 안에서 부른 부수효과는 이미 나간 뒤라 롤백을 못 따른다
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("commit failed");
                }
            });
            return null;
        }).when(outboxRepository).save(any());

        assertThatThrownBy(() -> paymentApplicationService.applyApprovedPaymentInTx(
                payment.getOrderId(), PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE)))
                .hasMessageContaining("commit failed");
        assertThat(fixture.statusOf(payment.getId())).as("롤백 뒤 결제 상태").isNotEqualTo("DONE");

        verify(paymentNotificationService, never()).clearCartItems(any(Order.class));
        verify(paymentNotificationService, never()).sendStockReducedNotifications(any(), any(Order.class));
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
        }).when(paymentNotificationService).clearCartItems(any(Order.class));
        doAnswer(invocation -> {
            statusAtNotification.set(fixture.statusOf(payment.getId()));
            return null;
        }).when(paymentNotificationService).sendStockReducedNotifications(any(), any(Order.class));

        paymentApplicationService.applyApprovedPaymentInTx(
                payment.getOrderId(), PaymentFixture.tossResponse(paymentKey, PaymentFixture.PRICE));

        assertThat(statusAtCartClear.get()).as("장바구니 비우기 호출 시점의 DB 결제 상태").isEqualTo("DONE");
        assertThat(statusAtNotification.get()).as("재고 알림 호출 시점의 DB 결제 상태").isEqualTo("DONE");
    }
}
