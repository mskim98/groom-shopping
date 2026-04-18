package groom.backend.infrastructure.payment;

import groom.backend.infrastructure.payment.dto.TossPaymentConfirmRequest;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;

public interface TossPaymentClient {

    /**
     * Toss Payments API로 결제 승인 요청 (멱등성 키 포함).
     *
     * @param idempotencyKey 재시도 시 이중 승인을 막기 위한 키 (paymentKey 권장).
     */
    TossPaymentResponse confirmPayment(TossPaymentConfirmRequest request, String idempotencyKey);

    /**
     * Toss Payments API로 결제 취소 요청 (멱등성 키 포함).
     *
     * @param idempotencyKey 보상 트랜잭션 재시도 시 이중 환불을 막기 위한 키.
     */
    TossPaymentResponse cancelPayment(String paymentKey, String cancelReason, String idempotencyKey);

    // 기존 호환용 오버로드: 멱등성 키 없이 호출할 때는 UUID를 새로 생성해 사용.
    default TossPaymentResponse confirmPayment(TossPaymentConfirmRequest request) {
        return confirmPayment(request, request.getPaymentKey());
    }

    default TossPaymentResponse cancelPayment(String paymentKey, String cancelReason) {
        return cancelPayment(paymentKey, cancelReason, paymentKey + ":cancel");
    }
}
