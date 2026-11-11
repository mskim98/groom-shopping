package groom.backend.application.payment;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// 이 인스턴스에서 컨슈머의 결과를 기다리는 confirm 들의 등록부 - 결제 id → 깨울 future
// Pub/Sub 메시지는 발행 순간 구독 중인 연결에만 닿으므로, 메시지는 대기를 일찍 깨우는 용도이고 답은 완료 키와 마지막 DB 읽기가 정한다
// confirm 한 번이 결제 하나를 기다리므로 id 하나에 대기자는 하나다. 다른 인스턴스의 결제 메시지도 오므로 등록 없는 id 는 무시한다
@Slf4j
@Component
public class ConfirmWaiters {

    private final ConcurrentHashMap<UUID, CompletableFuture<Long>> waiters = new ConcurrentHashMap<>();
    private final MeterRegistry meters;

    @Autowired
    public ConfirmWaiters(MeterRegistry meters) {
        this.meters = meters;
    }

    // 키를 읽기 전에 등록한다 - 등록 뒤에 온 메시지는 future 에 남고, 등록 전에 선 신호는 등록 직후의 키 읽기가 잡는다
    public CompletableFuture<Long> register(UUID paymentId) {
        CompletableFuture<Long> waiter = new CompletableFuture<>();
        waiters.put(paymentId, waiter);
        return waiter;
    }

    // 자기 future 일 때만 지운다 - 같은 결제의 confirm 이 동시에 둘이면 먼저 끝난 쪽이 나중 쪽의 등록을 지워 그쪽이 메시지를 놓친다
    public void remove(UUID paymentId, CompletableFuture<Long> waiter) {
        waiters.remove(paymentId, waiter);
    }

    // 메시지 본문 "<결제 id>:<신호 시각 epoch ms>". 깨질 수 없는 본문은 버린다 - 대기자는 폴링 간격 뒤 키 읽기로 깬다
    public void onMessage(String body) {
        int sep = body == null ? -1 : body.indexOf(':');
        if (sep < 0) {
            log.warn("[CONFIRM_WAKE_BAD_MESSAGE] 형식이 맞지 않는 메시지를 버림 - Body: {}", body);
            return;
        }
        try {
            CompletableFuture<Long> waiter = waiters.get(UUID.fromString(body.substring(0, sep)));
            if (waiter != null) {
                waiter.complete(Long.valueOf(body.substring(sep + 1)));
            }
        } catch (IllegalArgumentException e) {
            log.warn("[CONFIRM_WAKE_BAD_MESSAGE] 형식이 맞지 않는 메시지를 버림 - Body: {}", body);
        }
    }

    // 대기가 무엇으로 끝났는지 센다 - source = pubsub | key | timeout | db
    public void count(String source) {
        meters.counter("payment.confirm.wake", "source", source).increment();
    }

    int size() {
        return waiters.size();
    }
}
