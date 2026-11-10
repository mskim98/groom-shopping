package groom.backend.infrastructure.payment;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;

/**
 * bulkhead toss 의 동시 호출 한도를 넘어 Toss 를 부르지 않았다는 신호. 재시도(@Retry)의 ignore-exceptions 에 이 클래스를 올려
 * 한도가 찬 상태에서 같은 호출을 다시 밀어 넣지 않게 한다 - fallback 이 BulkheadFullException 을 바꿔 던지는 순간
 * 설정이 가리키던 원래 예외 타입이 사라지므로 바뀐 뒤의 타입을 따로 둔다(TossCircuitOpenException 과 같은 사정)
 */
public class TossBulkheadFullException extends BusinessException {

    public TossBulkheadFullException() {
        super(ErrorCode.PAYMENT_PG_BUSY);
    }
}
