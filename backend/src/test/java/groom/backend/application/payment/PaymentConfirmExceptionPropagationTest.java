package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.ProductStockService;
import groom.backend.application.product.StockDecrementer;
import groom.backend.application.raffle.RaffleTicketAllocationService;
import groom.backend.application.raffle.RaffleTicketApplicationService;
import groom.backend.application.raffle.RaffleValidationService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.domain.payment.repository.PaymentRepository;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.domain.raffle.repository.RaffleRepository;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("결제 확정 예외 전파")
class PaymentConfirmExceptionPropagationTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private TossPaymentClient tossPaymentClient;
    @Mock
    private RaffleRepository raffleRepository;
    @Mock
    private RaffleValidationService raffleValidationService;
    @Mock
    private RaffleTicketApplicationService raffleTicketApplicationService;
    @Mock
    private RaffleTicketAllocationService raffleTicketAllocationService;
    @Mock
    private PaymentNotificationService paymentNotificationService;
    @Mock
    private PaymentCompensationService paymentCompensationService;
    @Mock
    private PaymentOutboxRepository paymentOutboxRepository;
    @Mock
    private ProductStockService productStockService;
    // 생성자 인자로 추가된 차감 전략. 빠뜨리면 Mockito 가 null 을 넣어 조용히 통과했다가 나중에 NPE 로 터진다
    @Mock
    private StockDecrementer stockDecrementer;
    @Mock
    private ProductStockRedisRepository productStockRedisRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ObjectProvider<PaymentApplicationService> selfProvider;
    @InjectMocks
    private PaymentApplicationService service;

    private final UUID orderId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    private PaymentApplicationService stubHappyPathUntilDbStep() {
        Payment payment = mock(Payment.class);
        given(payment.isAlreadyApproved()).willReturn(false);
        given(payment.getAmountValue()).willReturn(1000);
        given(payment.getId()).willReturn(paymentId);
        given(paymentRepository.findByPaymentKey("pk-1")).willReturn(Optional.empty());
        given(paymentRepository.findByOrderId(orderId)).willReturn(Optional.of(payment));

        Order order = mock(Order.class);
        given(order.getOrderItems()).willReturn(List.of());
        given(orderRepository.findByIdWithItems(orderId)).willReturn(Optional.of(order));

        PaymentApplicationService proxy = mock(PaymentApplicationService.class);
        given(selfProvider.getObject()).willReturn(proxy);
        return proxy;
    }

    @Test
    @DisplayName("DB 후처리가 BusinessException 이면 errorCode 를 잃지 않고 그대로 전파한다")
    void dbPostProcessBusinessExceptionIsRethrownAsIs() {
        PaymentApplicationService proxy = stubHappyPathUntilDbStep();
        TossPaymentResponse response = mock(TossPaymentResponse.class);
        given(response.getPaymentKey()).willReturn("pk-1");
        given(tossPaymentClient.confirmPayment(any(), eq("pk-1"))).willReturn(response);
        given(proxy.applyApprovedPaymentInTx(orderId, response))
                .willThrow(new BusinessException(ErrorCode.PRODUCT_STOCK_CONFLICT));

        assertThatThrownBy(() -> service.confirmPayment("pk-1", orderId, 1000))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_STOCK_CONFLICT);
    }

    @Test
    @DisplayName("Toss 승인이 BusinessException 이면 errorCode 를 잃지 않고 그대로 전파한다")
    void tossConfirmBusinessExceptionIsRethrownAsIs() {
        stubHappyPathUntilDbStep();
        given(tossPaymentClient.confirmPayment(any(), eq("pk-1")))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE));

        assertThatThrownBy(() -> service.confirmPayment("pk-1", orderId, 1000))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE);
    }

    @Test
    @DisplayName("BusinessException 이 아닌 실패는 기존대로 RuntimeException 으로 감싼다")
    void nonBusinessExceptionKeepsWrapping() {
        PaymentApplicationService proxy = stubHappyPathUntilDbStep();
        TossPaymentResponse response = mock(TossPaymentResponse.class);
        given(response.getPaymentKey()).willReturn("pk-1");
        given(tossPaymentClient.confirmPayment(any(), eq("pk-1"))).willReturn(response);
        given(proxy.applyApprovedPaymentInTx(orderId, response))
                .willThrow(new IllegalStateException("티켓 발급 실패"));

        assertThatThrownBy(() -> service.confirmPayment("pk-1", orderId, 1000))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(BusinessException.class)
                .hasMessageContaining("결제 승인 후 처리 중 실패했습니다");
    }
}
