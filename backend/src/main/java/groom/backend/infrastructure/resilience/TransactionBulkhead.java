package groom.backend.infrastructure.resilience;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 연결을 쥐는 트랜잭션을 여는 장바구니 · 주문 요청 진입점에 붙인다. 허용을 트랜잭션이 시작되기 전에 잡는다(TransactionBulkheadAspect)
// 승인 후처리가 트랜잭션 안에서 부르는 메서드(clearOrderedItems)와 Kafka 컨슈머 · 결제 확정 경로에는 붙이지 않는다 - 돈이 이미 나간 단계는 거절하지 않는다
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TransactionBulkhead {
}
