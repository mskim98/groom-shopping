package groom.backend.infrastructure.payment;

/**
 * 토스가 이미 취소 처리를 마친 결제에 취소를 재요청했을 때(400 ALREADY_CANCELED_PAYMENT) 던진다
 * 이 응답은 실패가 아니라 목적 달성과 같으므로 성공으로 기록한다
 */
public class TossAlreadyCanceledException extends RuntimeException {

    public TossAlreadyCanceledException(String message) {
        super(message);
    }
}
