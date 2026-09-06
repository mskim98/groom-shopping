package groom.backend.infrastructure.payment;

/**
 * 토스 취소 응답이 일시적 재시도 대기를 지시할 때 던진다
 * 대상 - 429 · 403 FORBIDDEN_CONSECUTIVE_REQUEST · 409 IDEMPOTENT_REQUEST_PROCESSING
 * 셋 다 토스가 살아 있다는 신호라 실패로 세지 않고 다음 시각만 미룬다
 */
public class TossRetryLaterException extends RuntimeException {

    public TossRetryLaterException(String message) {
        super(message);
    }
}
