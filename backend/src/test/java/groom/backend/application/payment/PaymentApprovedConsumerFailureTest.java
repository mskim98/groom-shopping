package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.kafka.PaymentEventConsumer;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.context.TestPropertySource;

// 후처리가 재고 부족으로 끝날 때 컨슈머가 보상 의도 기록 → Redis 복원 → FAILED 순으로 처리하고 예외를 던지지 않는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=1000",
        "payment.confirm.poll-interval-ms=50",
        "payment.compensation.retry-delay-ms=600000" // 배경 보상 배치가 이 시험의 보상 행을 가져가지 않게 늦춘다
})
@DisplayName("결제 승인 컨슈머 실패 경로(재고 부족 보상)")
class PaymentApprovedConsumerFailureTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 실패 때 호출되지 않는지 확인하는 지점
    // 보상 의도 기록 호출 순서를 관찰한다(실제 기록은 그대로 한다)
    @SpyBean
    private PaymentCompensationService compensationService;
    // Redis 복원 호출을 관찰한다. 시험이 Redis 에 값을 남기지 않게 복원은 호출만 기록하고 실행하지 않는다
    @SpyBean
    private ProductStockRedisRepository productStockRedisRepository;

    @Autowired
    private PaymentEventConsumer consumer;
    @Autowired
    private ObjectMapper objectMapper;
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
        productId = fixture.product(0); // 재고 0 에 1개 주문 → 후처리의 재고 차감이 실패한다
        payment = fixture.payment(productId, 1);
        paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    private PaymentApprovedEvent event() {
        return PaymentFixture.approvedEvent(payment, productId, 1, paymentKey);
    }

    @Test
    @DisplayName("재고 부족으로 후처리가 실패하면 예외 없이 보상 의도 기록 → Redis 복원 → FAILED 순으로 처리한다")
    void consume_재고부족_예외를_던지지_않고_보상_복원_FAILED를_이_순서로_한다() {
        List<String> calls = new CopyOnWriteArrayList<>();
        AtomicReference<String> statusAtRelease = new AtomicReference<>();
        doAnswer(invocation -> {
            calls.add("compensate");
            return invocation.callRealMethod();
        }).when(compensationService).compensate(any(), any(), any(), any());
        doAnswer(invocation -> {
            calls.add("release");
            statusAtRelease.set(fixture.statusOf(payment.getId()));
            return null;
        }).when(productStockRedisRepository).release(any(), anyInt());

        assertThatCode(() -> consumer.consume(PaymentFixture.record(objectMapper, event())))
                .doesNotThrowAnyException();

        assertThat(calls).containsExactly("compensate", "release");
        assertThat(statusAtRelease.get()).as("복원 시점에는 아직 FAILED 가 아니다").isEqualTo("READY");
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("후처리가 롤백되면 재고 알림과 장바구니 비우기를 부르지 않는다")
    void consume_재고부족_알림과_장바구니비우기는_부르지_않는다() {
        assertThatCode(() -> consumer.consume(PaymentFixture.record(objectMapper, event())))
                .doesNotThrowAnyException();

        verify(paymentNotificationService, never()).sendStockReducedNotifications(any(), any());
        verify(paymentNotificationService, never()).clearCartItems(any(), any(), any());
    }

    @Test
    @DisplayName("재고 부족 실패는 보상 행을 PENDING 으로 남기고 결제는 FAILED 에 오류 코드를 기록한다")
    void consume_재고부족_보상행은_PENDING이고_결제는_FAILED에_오류코드가_기록된다() throws Exception {
        consumer.consume(PaymentFixture.record(objectMapper, event()));

        assertThat(jdbc.queryForList("SELECT status FROM payment_compensation WHERE payment_id = ?",
                String.class, payment.getId())).containsExactly("PENDING");
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
        assertThat(fixture.failureCodeOf(payment.getId())).isEqualTo(ErrorCode.INSUFFICIENT_STOCK.name());
        assertThat(fixture.orderStatusOf(payment.getOrderId())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("후처리 전에 이미 DONE 이면 보상 없이 멱등하게 끝난다")
    void consume_후처리_전에_이미_DONE이면_보상없이_끝난다() {
        // 같은 주문을 먼저 확정한 쪽이 이미 DONE 으로 만든 상태
        jdbc.update("UPDATE payment SET status = 'DONE' WHERE id = ?", payment.getId());

        assertThatCode(() -> consumer.consume(PaymentFixture.record(objectMapper, event())))
                .doesNotThrowAnyException();

        assertThat(fixture.compensationCountOf(payment.getId())).isZero();
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
    }
}
