package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import groom.backend.application.product.ConditionalUpdateStockDecrementer;
import groom.backend.common.exception.BusinessException;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
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

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = "spring.kafka.streams.auto-startup=false")
@DisplayName("결제 후처리 롤백 시 재고 원복(T3-1)")
class PaymentPostProcessRollbackTest {

    private static final int INITIAL_STOCK = 10;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    // 실제 차감은 그대로 하고 그 뒤에 후처리를 실패시키는 주입 지점(Outbox 적재는 확정 쪽 트랜잭션으로 옮겨져 후처리에 없다)
    @SpyBean
    private ConditionalUpdateStockDecrementer stockDecrementer;
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
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("post-process down");
        }).when(stockDecrementer).decrease(any(), anyInt());

        assertThatThrownBy(() -> paymentApplicationService.applyApprovedPaymentInTx(
                payment.getOrderId(), PaymentFixture.tossResponse("pk-" + UUID.randomUUID(), PaymentFixture.PRICE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("post-process down");

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

    @Test
    @DisplayName("다중 상품 주문에서 두 번째 상품의 차감이 실패하면 첫 번째 상품의 차감도 되돌아온다")
    void 후처리실패_둘째상품_재고부족_첫째상품_재고가_복원된다() {
        // 차감은 상품 id 오름차순으로 잠그므로, id 가 작은 쪽이 첫 번째 · 큰 쪽(재고 0)이 두 번째로 실패한다
        UUID a = fixture.product(INITIAL_STOCK);
        UUID b = fixture.product(0);
        UUID first = a.compareTo(b) < 0 ? a : b;
        UUID second = first.equals(a) ? b : a;
        // first 는 재고 10, second 는 재고 0 이어야 하므로 필요하면 서로 바꿔 채운다
        jdbcSetStock(first, INITIAL_STOCK);
        jdbcSetStock(second, 0);
        Payment twoItems = fixture.payment(first, 1, second, 1);
        try {
            assertThatThrownBy(() -> paymentApplicationService.applyApprovedPaymentInTx(
                    twoItems.getOrderId(), PaymentFixture.tossResponse("pk-" + UUID.randomUUID(), PaymentFixture.PRICE * 2)))
                    .isInstanceOf(BusinessException.class);

            assertThat(fixture.stockOf(first)).as("먼저 차감된 상품의 재고").isEqualTo(INITIAL_STOCK);
            assertThat(fixture.stockOf(second)).isZero();
            assertThat(fixture.statusOf(twoItems.getId())).isNotEqualTo("DONE");
        } finally {
            fixture.cleanup(twoItems, first);
            fixture.deleteProduct(second);
        }
    }

    private void jdbcSetStock(UUID productId, int stock) {
        jdbc.update("UPDATE product SET stock = ? WHERE id = ?", stock, productId);
    }
}
