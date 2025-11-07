package groom.backend.infrastructure.payment;

import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;

public interface TossPaymentClient {

    /**
     * Toss Payments API로 결제 승인 요청
     */
    TossPaymentResponse confirmPayment(TossPaymentConfirmRequest request);

    /**
     * Toss Payments API로 결제 취소 요청
     */
    TossPaymentResponse cancelPayment(String paymentKey, String cancelReason);
}
