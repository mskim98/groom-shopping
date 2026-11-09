package groom.backend.infrastructure.payment;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;

/**
 * toss 서킷이 OPEN 이라 Toss 를 호출하지 않았다는 신호. 재시도(@Retry)의 ignore-exceptions 에 이 클래스를 올려
 * OPEN 인데도 재시도 대기(200ms + 400ms)를 기다리는 일을 막는다
 * confirmPaymentFallback 이 CallNotPermittedException 을 BusinessException 으로 바꿔 던지는 순간
 * 재시도 설정이 가리키던 원래 예외 타입이 사라지므로, 바뀐 뒤의 타입을 따로 둔다
 */
public class TossCircuitOpenException extends BusinessException {

    public TossCircuitOpenException() {
        super(ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE);
    }
}
