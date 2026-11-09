package groom.backend.interfaces.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import groom.backend.application.payment.PaymentApplicationService;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentStatus;
import groom.backend.interfaces.payment.dto.request.ConfirmPaymentRequest;
import groom.backend.interfaces.payment.dto.response.PaymentResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제 확정 응답 코드")
class PaymentConfirmResponseCodeTest {

    @Mock
    private PaymentApplicationService paymentApplicationService;

    private PaymentController controller;

    private final UUID orderId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final User user = mock(User.class);

    @BeforeEach
    void setUp() {
        controller = new PaymentController(paymentApplicationService);
    }

    private Payment paymentWith(PaymentStatus status) {
        Payment payment = mock(Payment.class);
        given(payment.getId()).willReturn(paymentId);
        given(payment.getStatus()).willReturn(status);
        return payment;
    }

    @Test
    @DisplayName("서비스가 DONE을 돌려주면 200이다")
    void confirm_서비스가_DONE을_돌려주면_200이다() {
        Payment payment = paymentWith(PaymentStatus.DONE);
        given(paymentApplicationService.confirmPayment(eq("pk-1"), eq(orderId), any())).willReturn(payment);

        ResponseEntity<PaymentResponse> response =
                controller.confirmPayment(user, new ConfirmPaymentRequest("pk-1", orderId, 1000));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("서비스가 READY를 돌려주면 202에 같은 본문이다")
    void confirm_서비스가_READY를_돌려주면_202에_같은_본문이다() {
        Payment payment = paymentWith(PaymentStatus.READY);
        given(paymentApplicationService.confirmPayment(eq("pk-1"), eq(orderId), any())).willReturn(payment);

        ResponseEntity<PaymentResponse> response =
                controller.confirmPayment(user, new ConfirmPaymentRequest("pk-1", orderId, 1000));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().getId()).isEqualTo(paymentId);
        assertThat(response.getBody().getStatus()).isEqualTo(PaymentStatus.READY);
    }
}
