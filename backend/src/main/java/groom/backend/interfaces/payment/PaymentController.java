package groom.backend.interfaces.payment;

import groom.backend.application.payment.PaymentApplicationService;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.payment.model.Payment;
import groom.backend.interfaces.payment.dto.request.CancelPaymentRequest;
import groom.backend.interfaces.payment.dto.request.ConfirmPaymentRequest;
import groom.backend.interfaces.payment.dto.request.PreparePaymentRequest;
import groom.backend.interfaces.payment.dto.response.PaymentResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentApplicationService paymentApplicationService;

    /**
     * 결제 준비 - Order 기반으로 Payment 생성
     */
    @PostMapping("/prepare")
    public ResponseEntity<PaymentResponse> preparePayment(
            @AuthenticationPrincipal User user,
            @RequestBody PreparePaymentRequest request) {

        log.info("[API_REQUEST] Prepare payment - UserId: {}, OrderId: {}",
                user.getId(), request.getOrderId());

        Payment payment = paymentApplicationService.preparePayment(
                request.getOrderId(),
                request.getPaymentMethod()
        );

        PaymentResponse response = PaymentResponse.from(payment);

        log.info("[API_RESPONSE] Payment prepared - PaymentId: {}", response.getId());

        return ResponseEntity.ok(response);
    }

    /**
     * 결제 승인 - Toss Payments API 호출 후 상태 변경
     */
    @PostMapping("/confirm")
    public ResponseEntity<PaymentResponse> confirmPayment(
            @AuthenticationPrincipal User user,
            @RequestBody ConfirmPaymentRequest request) {

        log.info("[API_REQUEST] Confirm payment - UserId: {}, OrderId: {}, PaymentKey: {}",
                user.getId(), request.getOrderId(), request.getPaymentKey());

        Payment payment = paymentApplicationService.confirmPayment(
                request.getPaymentKey(),
                request.getOrderId(),
                request.getAmount()
        );

        PaymentResponse response = PaymentResponse.from(payment);

        log.info("[API_RESPONSE] Payment confirmed - PaymentId: {}, Status: {}",
                response.getId(), response.getStatus());

        return ResponseEntity.ok(response);
    }

    /**
     * 테스트용 결제 승인 (Toss API 호출 없이)
     */
    @PostMapping("/confirm/test")
    public ResponseEntity<PaymentResponse> confirmPaymentForTest(
            @AuthenticationPrincipal(expression = "user") User user,
            @RequestBody ConfirmPaymentRequest request) {

        log.info("[API_REQUEST] Test confirm payment - UserId: {}, OrderId: {}",
                user.getId(), request.getOrderId());

        Payment payment = paymentApplicationService.confirmPaymentForTest(request.getOrderId());

        PaymentResponse response = PaymentResponse.from(payment);

        log.info("[API_RESPONSE] Test payment confirmed - PaymentId: {}, Status: {}",
                response.getId(), response.getStatus());

        return ResponseEntity.ok(response);
    }

    /**
     * 결제 취소
     */
    @PostMapping("/cancel")
    public ResponseEntity<PaymentResponse> cancelPayment(
            @AuthenticationPrincipal User user,
            @RequestBody CancelPaymentRequest request) {

        log.info("[API_REQUEST] Cancel payment - UserId: {}, PaymentId: {}, Reason: {}",
                user.getId(), request.getPaymentId(), request.getCancelReason());

        Payment payment = paymentApplicationService.cancelPayment(
                request.getPaymentId(),
                request.getCancelReason()
        );

        PaymentResponse response = PaymentResponse.from(payment);

        log.info("[API_RESPONSE] Payment cancelled - PaymentId: {}, Status: {}",
                response.getId(), response.getStatus());

        return ResponseEntity.ok(response);
    }

    /**
     * 결제 조회
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @AuthenticationPrincipal User user,
            @PathVariable UUID paymentId) {

        log.info("[API_REQUEST] Get payment - UserId: {}, PaymentId: {}",
                user.getId(), paymentId);

        Payment payment = paymentApplicationService.getPayment(paymentId);
        PaymentResponse response = PaymentResponse.from(payment);

        return ResponseEntity.ok(response);
    }

    /**
     * 주문의 결제 조회
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResponse> getPaymentByOrderId(
            @AuthenticationPrincipal User user,
            @PathVariable UUID orderId) {

        log.info("[API_REQUEST] Get payment by order - UserId: {}, OrderId: {}",
                user.getId(), orderId);

        Payment payment = paymentApplicationService.getPaymentByOrderId(orderId);
        PaymentResponse response = PaymentResponse.from(payment);

        return ResponseEntity.ok(response);
    }

    /**
     * 사용자의 결제 목록 조회
     */
    @GetMapping("/my")
    public ResponseEntity<List<PaymentResponse>> getMyPayments(
            @AuthenticationPrincipal User user) {

        log.info("[API_REQUEST] Get my payments - UserId: {}", user.getId());

        List<Payment> payments = paymentApplicationService.getPaymentsByUserId(user.getId());
        List<PaymentResponse> responses = payments.stream()
                .map(PaymentResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }
}
