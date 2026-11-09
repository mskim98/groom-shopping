package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = "spring.kafka.streams.auto-startup=false")
@DisplayName("결제 후처리 롤백 시 재고 원복(T3-1)")
class PaymentPostProcessRollbackTest {

    private static final int INITIAL_STOCK = 10;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    // 재고 차감 뒤에 실행되는 Outbox 적재를 실패시키는 주입 지점. 폴러(PaymentEventPublisher)도 이 목을 읽는다
    @MockBean
    private PaymentOutboxRepository outboxRepository;
    // 비동기 알림·장바구니 호출이 시험 DB 에 부수효과를 남기지 않게 막는다
    @MockBean
    private PaymentNotificationService paymentNotificationService;

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

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(INITIAL_STOCK);
        payment = fixture.payment(productId, 1);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup(payment, productId);
    }

    @Test
    @DisplayName("재고 차감 뒤 후처리가 예외로 끝나면 DB 재고가 차감 전 값으로 돌아오고 결제는 DONE 이 아니다")
    void 후처리실패_재고차감뒤_예외_DB재고가_차감전값이다() {
        doThrow(new IllegalStateException("outbox down")).when(outboxRepository).save(any());

        assertThatThrownBy(() -> paymentApplicationService.applyApprovedPaymentInTx(
                payment.getOrderId(), PaymentFixture.tossResponse("pk-" + UUID.randomUUID(), PaymentFixture.PRICE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox down");

        assertThat(fixture.stockOf(productId)).as("롤백 뒤 DB 재고").isEqualTo(INITIAL_STOCK);
        assertThat(fixture.statusOf(payment.getId())).as("결제 상태").isNotEqualTo("DONE");
    }

    @Test
    @DisplayName("후처리가 정상 종료되면 DB 재고가 주문 수량만큼 차감되고 결제는 DONE 이다")
    void 후처리성공_정상경로_DB재고가_차감된다() {
        paymentApplicationService.applyApprovedPaymentInTx(
                payment.getOrderId(), PaymentFixture.tossResponse("pk-" + UUID.randomUUID(), PaymentFixture.PRICE));

        assertThat(fixture.stockOf(productId)).as("커밋 뒤 DB 재고").isEqualTo(INITIAL_STOCK - 1);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
    }
}
