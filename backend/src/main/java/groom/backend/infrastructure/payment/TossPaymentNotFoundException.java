package groom.backend.infrastructure.payment;

/**
 * Toss 가 취소 요청에 "결제 없음"(404 NOT_FOUND_PAYMENT)으로 답했다 - 승인된 적이 없는 paymentKey 다.
 * 승인 응답이 시간 초과로 끝난 확정에 걸어 둔 보상이 실제로는 승인되지 않았던 경우이며, 재시도해도 결과가 같다
 */
public class TossPaymentNotFoundException extends RuntimeException {

    public TossPaymentNotFoundException(String message) {
        super(message);
    }
}
