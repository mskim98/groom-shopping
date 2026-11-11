package groom.backend.infrastructure.resilience;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 연결을 쥐는 트랜잭션을 여는 장바구니 · 주문 요청 진입점에 붙인다. 허용을 트랜잭션이 시작되기 전에 잡는다(TransactionBulkheadAspect)
// 승인 후처리는 더 이상 장바구니를 비우지 않는다(clearOrderedItems 는 결제 흐름에서 부르지 않는다). Kafka 컨슈머 · 결제 확정 경로에는 붙이지 않는다 - 돈이 이미 나간 단계는 거절하지 않는다
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TransactionBulkhead {
}
