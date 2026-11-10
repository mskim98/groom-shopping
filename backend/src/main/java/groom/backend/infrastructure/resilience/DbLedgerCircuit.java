package groom.backend.infrastructure.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 컨슈머의 DB 작업을 회로 {@code db-ledger} 로 감싸고, 회로(자기 상태 또는 공유 키)가 OPEN 인 동안 리스너 컨테이너를 멈춘다
 *
 * <p>DB 가 죽었을 때 레코드를 계속 읽으면 재시도 5회(약 31초)를 태운 뒤 payment-events-dlq 로 쌓인다. 멈춰 두면
 * 레코드가 승인되지 않은 채 토픽에 남아 DB 가 돌아온 뒤 순서대로 처리된다
 */
@Slf4j
@Component
public class DbLedgerCircuit {

    public static final String NAME = "db-ledger";
    public static final String LISTENER_ID = "payment-event-listener";

    private final CircuitBreakerRegistry registry;
    private final SharedCircuitFlag flag;
    private final KafkaListenerEndpointRegistry listeners;

    public DbLedgerCircuit(CircuitBreakerRegistry registry, SharedCircuitFlag flag, KafkaListenerEndpointRegistry listeners) {
        this.registry = registry;
        this.flag = flag;
        this.listeners = listeners;
    }

    /** 회로를 거쳐 작업을 실행한다. 열려 있으면(자기 회로 · 공유 키) 작업을 실행하지 않고 CallNotPermittedException 을 낸다 */
    public void run(Runnable work) {
        registry.circuitBreaker(NAME).executeRunnable(() -> {
            flag.failFastIfOpen(NAME);
            work.run();
        });
    }

    public boolean isOpen() {
        return registry.circuitBreaker(NAME).getState() == CircuitBreaker.State.OPEN || flag.isOpen(NAME);
    }

    @Scheduled(fixedDelayString = "${payment.consumer.circuit-check-ms:1000}")
    public void check() {
        MessageListenerContainer container = listeners == null ? null : listeners.getListenerContainer(LISTENER_ID);
        if (container != null) {
            checkOnce(container);
        }
    }

    public void checkOnce(MessageListenerContainer container) {
        boolean open = isOpen();
        if (open && !container.isPauseRequested()) {
            container.pause();
            log.warn("[DB_LEDGER_CONSUMER_PAUSED] db-ledger 회로가 열려 결제 컨슈머를 멈춘다");
        } else if (!open && container.isPauseRequested()) {
            container.resume();
            log.warn("[DB_LEDGER_CONSUMER_RESUMED] db-ledger 회로가 풀려 결제 컨슈머를 재개한다");
        }
    }
}
