package groom.backend.infrastructure.resilience;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 가상 스레드는 요청마다 한꺼번에 돌아 50 연결 풀 앞에 트랜잭션이 몰리고, 풀을 기다리는 동안에도 앞선 트랜잭션의 연결은 쥔 채라 응답이 늘어진다(r120 Diagnosis 2 B1)
// 그래서 트랜잭션이 시작되기 전에 허용을 잡는다. 허용이 없으면 대기 상한까지만 기다리고 503 으로 돌려보낸다
// 가장 바깥에서 도는 순서(HIGHEST_PRECEDENCE)라야 @Transactional 프록시가 연결을 잡기 전에 이 허용이 먼저 잡힌다
// 한도는 resilience4j.bulkhead.instances.transaction (application-dev.yml)
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransactionBulkheadAspect {

    public static final String INSTANCE = "transaction";

    // 이미 허용을 쥔 스레드가 격벽 메서드를 다시 부르면(진입점이 진입점을 부르는 경우) 두 번째 허용을 기다리다 서로 막히지 않게 그대로 지나간다
    private static final ThreadLocal<Boolean> HOLDING = new ThreadLocal<>();

    private final Bulkhead bulkhead;

    public TransactionBulkheadAspect(BulkheadRegistry registry) {
        this.bulkhead = registry.bulkhead(INSTANCE);
    }

    @Around("@annotation(groom.backend.infrastructure.resilience.TransactionBulkhead)")
    public Object guard(ProceedingJoinPoint joinPoint) throws Throwable {
        if (HOLDING.get() != null) {
            return joinPoint.proceed();
        }
        try {
            bulkhead.acquirePermission();
        } catch (BulkheadFullException full) {
            throw new BusinessException(ErrorCode.TRANSACTION_BULKHEAD_FULL);
        }
        HOLDING.set(Boolean.TRUE);
        try {
            return joinPoint.proceed();
        } finally {
            HOLDING.remove();
            bulkhead.onComplete();
        }
    }
}
