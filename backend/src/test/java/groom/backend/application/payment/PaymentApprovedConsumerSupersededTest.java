package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.product.ConditionalUpdateStockDecrementer;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.kafka.PaymentEventConsumer;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// 후처리 도중 다른 쪽이 결제를 DONE 으로 먼저 바꾼 경우 보상 없이 FAILED 로 전이하지 않고 예외 없이 끝나는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=1000",
        "payment.confirm.poll-interval-ms=50",
        "payment.compensation.retry-delay-ms=600000" // 배경 보상 배치가 이 시험의 보상 행을 가져가지 않게 늦춘다
})
@DisplayName("후처리 도중 다른 쪽이 먼저 DONE 으로 만든 결제의 실패 처리")
class PaymentApprovedConsumerSupersededTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private PaymentNotificationService paymentNotificationService; // 실패 때 호출되지 않는지 확인하는 지점
    // 재고 차감 호출을 관찰한다. 후처리 도중 DONE 으로 바뀌는 지점을 끼워 넣는 데 쓴다
    @SpyBean
    private ConditionalUpdateStockDecrementer stockDecrementer;
    // 보상 의도 기록 호출 여부를 관찰한다(실제 기록은 그대로 한다)
    @SpyBean
    private PaymentCompensationService compensationService;

    @Autowired
    private PlatformTransactionManager transactionManager;
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
        productId = fixture.product(10); // 재고 10 에 1개 주문 → 후처리의 재고 차감은 성공하는 조건
        payment = fixture.payment(productId, 1);
        paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("후처리 도중 다른 쪽이 결제를 DONE 으로 바꾸면 보상 없이 FAILED 전이 없이 예외 없이 끝난다")
    void consume_후처리도중_DONE으로_바뀌면_보상없이_FAILED전이없이_예외없이_끝난다() {
        // 후처리가 READY 가드를 통과해 재고 차감까지 간 뒤에 같은 결제의 다른 후처리가 먼저 DONE 으로 커밋한 상황을 만든다
        // SQL 은 후처리 트랜잭션이 결제 행을 쓰기 전에(차감 호출 직전) 실행해 행 잠금 대기를 피한다
        // JdbcTemplate 을 그냥 부르면 후처리 트랜잭션의 커넥션에 합류해 롤백과 함께 사라진다 - REQUIRES_NEW 로 별도 커밋한다
        TransactionTemplate otherTx = new TransactionTemplate(transactionManager);
        otherTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            otherTx.executeWithoutResult(status ->
                    jdbc.update("UPDATE payment SET status = 'DONE' WHERE id = ?", payment.getId()));
            invocation.callRealMethod();
            throw new IllegalStateException("lost the race");
        }).when(stockDecrementer).decrease(any(), anyInt());

        assertThatCode(() -> consumer.consume(PaymentFixture.record(objectMapper,
                PaymentFixture.approvedEvent(payment, productId, 1, paymentKey))))
                .doesNotThrowAnyException();

        assertThat(fixture.compensationCountOf(payment.getId())).as("정상 승인된 결제를 취소할 보상 행").isZero();
        verify(compensationService, never()).compensate(any(), any(), any(), any());
        assertThat(fixture.statusOf(payment.getId())).as("FAILED 로 전이하지 않는다").isEqualTo("DONE");
        assertThat(fixture.stockOf(productId)).as("진 쪽의 재고 차감은 롤백된다").isEqualTo(10);
    }
}
