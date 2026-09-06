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

    /**
     * 배치 재시도 전용 취소 - 회로("toss-cancel")에 계수되지 않는다. 한 페이지 대량 실패가 회로 창을 채워
     * 사용자 수동 취소 엔드포인트를 fast-fail 시키는 것을 막는다(결정 C). HTTP 호출·응답 분류는 계수 버전과 동일.
     */
    TossPaymentResponse cancelPaymentUncounted(String paymentKey, String cancelReason, String idempotencyKey);

    // 기존 호환용 오버로드: 멱등성 키 없이 호출할 때는 UUID를 새로 생성해 사용.
    default TossPaymentResponse confirmPayment(TossPaymentConfirmRequest request) {
        return confirmPayment(request, request.getPaymentKey());
    }

    default TossPaymentResponse cancelPayment(String paymentKey, String cancelReason) {
        return cancelPayment(paymentKey, cancelReason, paymentKey + ":cancel");
    }
}
