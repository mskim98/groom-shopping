package groom.backend.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import groom.backend.application.payment.PaymentApplicationService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = "spring.kafka.streams.auto-startup=false")
@DisplayName("확정 fallback 분류와 paymentKey 저장(T3-13)")
class ConfirmFallbackClassificationTest {

    private static final TossPaymentConfirmRequest REQUEST = new TossPaymentConfirmRequest("pk-fb", "ord", 1000);

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private TossPaymentClient tossPaymentClient; // 확정 호출을 실패시키는 주입 지점

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

    // 이 클래스의 fallback 은 프록시를 거치지 않고 직접 부른다. 필드(RestTemplate)는 fallback 이 쓰지 않는다
    private final TossPaymentClientImpl client = new TossPaymentClientImpl(null, null, null);

    private ErrorCode classify(Throwable cause) {
        BusinessException thrown = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> client.confirmPaymentFallback(REQUEST, "pk-fb", cause));
        return thrown.getErrorCode();
    }

    @Test
    @DisplayName("연결·읽기 시간 초과는 PAYMENT_PG_TIMEOUT 으로 분류한다")
    void fallback_시간초과_PAYMENT_PG_TIMEOUT으로_분류한다() {
        Throwable timeout = new RuntimeException("Toss Payments API 호출 실패",
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertThat(classify(timeout)).isEqualTo(ErrorCode.PAYMENT_PG_TIMEOUT);
        assertThat(classify(new SocketTimeoutException("Read timed out"))).isEqualTo(ErrorCode.PAYMENT_PG_TIMEOUT);
    }

    @Test
    @DisplayName("서킷 거절(CallNotPermittedException)은 PAYMENT_TEMPORARILY_UNAVAILABLE 로 분류한다")
    void fallback_서킷거절_PAYMENT_TEMPORARILY_UNAVAILABLE로_분류한다() {
        CircuitBreaker open = CircuitBreaker.ofDefaults("classification");
        open.transitionToOpenState();

        assertThat(classify(CallNotPermittedException.createCallNotPermittedException(open)))
                .isEqualTo(ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE);
    }

    @Test
    @DisplayName("카드 거절 등 Toss 4xx 응답은 PAYMENT_PG_REJECTED 로 분류한다")
    void fallback_카드거절4xx_PAYMENT_PG_REJECTED로_분류한다() {
        HttpClientErrorException rejected = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                new HttpHeaders(), "{\"code\":\"REJECT_CARD_PAYMENT\"}".getBytes(StandardCharsets.UTF_8), null);

        assertThat(classify(new RuntimeException("Toss Payments API 호출 실패", rejected)))
                .isEqualTo(ErrorCode.PAYMENT_PG_REJECTED);
    }

    @Test
    @DisplayName("409 IDEMPOTENT_REQUEST_PROCESSING 은 앞선 같은 키 요청이 처리 중이라 결과 불명이므로 PAYMENT_PG_TIMEOUT 으로 분류한다")
    void fallback_멱등키처리중409_PAYMENT_PG_TIMEOUT으로_분류한다() {
        HttpClientErrorException processing = HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict",
                new HttpHeaders(),
                "{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\",\"message\":\"처리 중\"}".getBytes(StandardCharsets.UTF_8), null);

        assertThat(classify(new RuntimeException("Toss Payments API 호출 실패", processing)))
                .isEqualTo(ErrorCode.PAYMENT_PG_TIMEOUT);
    }

    @Test
    @DisplayName("다른 코드의 409 는 확정 거절이라 PAYMENT_PG_REJECTED 로 분류한다")
    void fallback_다른코드409_PAYMENT_PG_REJECTED로_분류한다() {
        HttpClientErrorException conflict = HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict",
                new HttpHeaders(),
                "{\"code\":\"DUPLICATED_ORDER_ID\"}".getBytes(StandardCharsets.UTF_8), null);

        assertThat(classify(new RuntimeException("Toss Payments API 호출 실패", conflict)))
                .isEqualTo(ErrorCode.PAYMENT_PG_REJECTED);
    }

    @Test
    @DisplayName("분류 밖의 실패(5xx 등)는 PAYMENT_PG_FAILURE 로 남긴다")
    void fallback_그외실패_PAYMENT_PG_FAILURE로_분류한다() {
        assertThat(classify(new IllegalStateException("5xx"))).isEqualTo(ErrorCode.PAYMENT_PG_FAILURE);
    }

    @Test
    @DisplayName("Toss 확정이 실패하면 FAILED 행에 TOSS_CONFIRM_FAILED 코드와 paymentKey 가 저장된다")
    void 확정실패_TOSS_CONFIRM_FAILED행에_paymentKey가_저장된다() {
        UUID productId = productRepository.save(ProductJpaEntity.builder()
                .id(UUID.randomUUID()).name("fixture-product").price(1000).stock(10)
                .isActive(true).category("GENERAL").status("AVAILABLE").build()).getId();
        Order order = Order.builder().userId(1L).couponId(null).status(OrderStatus.PENDING).build();
        order.addOrderItem(OrderItem.builder()
                .productId(productId).productName("fixture-product").price(1000).quantity(1).build());
        order.calculateAmounts();
        order = orderRepository.save(order);
        Payment payment = paymentRepository.save(Payment.builder().order(order).userId(1L).amount(1000)
                .orderName("fixture-order").customerName("tester").method(PaymentMethod.CARD).build());
        String paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_PG_REJECTED));
        try {
            assertThatThrownBy(() -> paymentApplicationService.confirmPayment(paymentKey, payment.getOrderId(), 1000))
                    .isInstanceOf(BusinessException.class);

            assertThat(jdbc.queryForMap("SELECT status, failure_code, payment_key FROM payment WHERE id = ?",
                    payment.getId()))
                    .containsEntry("status", "FAILED")
                    .containsEntry("failure_code", "TOSS_CONFIRM_FAILED")
                    .containsEntry("payment_key", paymentKey);
        } finally {
            paymentRepository.deleteById(payment.getId());
            orderRepository.deleteById(payment.getOrderId());
            productRepository.deleteById(productId);
        }
    }
}
