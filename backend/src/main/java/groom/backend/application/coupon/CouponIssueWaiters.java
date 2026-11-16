package groom.backend.application.coupon;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 이 인스턴스에서 컨슈머의 확정을 기다리는 발급 요청들의 등록부 - requestId → 깨울 future
 *
 * <p>Pub/Sub 메시지는 발행 순간 구독 중인 연결에만 닿으므로 메시지는 대기를 일찍 깨우는 용도이고, 답은 상태 키가 정한다.
 * 다른 인스턴스의 요청 메시지도 오므로 등록 없는 requestId 는 무시한다. 결제 {@link groom.backend.application.payment.ConfirmWaiters} 와 같은 구조다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueWaiters {

    private final ConcurrentHashMap<String, CompletableFuture<String>> waiters = new ConcurrentHashMap<>();
    private final MeterRegistry meters;

    // 키를 읽기 전에 등록한다 - 등록 뒤에 온 메시지는 future 에 남고, 등록 전에 선 신호는 등록 직후의 키 읽기가 잡는다
    public CompletableFuture<String> register(String requestId) {
        CompletableFuture<String> waiter = new CompletableFuture<>();
        waiters.put(requestId, waiter);
        return waiter;
    }

    // 자기 future 일 때만 지운다 - 같은 requestId 의 등록을 다른 쪽이 지우지 않게 한다
    public void remove(String requestId, CompletableFuture<String> waiter) {
        waiters.remove(requestId, waiter);
    }

    // 메시지 본문 "<requestId>:<SUCCESS|FAILED>". 형식이 깨진 본문은 버린다 - 대기자는 재확인 간격 뒤 키 읽기로 깬다
    public void onMessage(String body) {
        int sep = body == null ? -1 : body.lastIndexOf(':');
        if (sep < 0) {
            log.warn("[COUPON_WAKE_BAD_MESSAGE] 형식이 맞지 않는 메시지를 버림 - Body: {}", body);
            return;
        }
        CompletableFuture<String> waiter = waiters.get(body.substring(0, sep));
        if (waiter != null) {
            waiter.complete(body.substring(sep + 1));
        }
    }

    // 대기가 무엇으로 끝났는지 센다 - source = pubsub | key | fallback
    public void count(String source) {
        meters.counter("coupon.issue.wake", "source", source).increment();
    }

    public int size() {
        return waiters.size();
    }
}
