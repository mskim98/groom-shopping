package groom.backend.application.payment;

import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 결제가 READY 를 벗어났다(DONE · FAILED)는 사실을 DB 밖에 알리는 짧은 Redis 신호
// 기다리는 confirm 이 폴링마다 연결 풀에서 연결을 빌려 결제를 읽으면 50 개짜리 풀을 승인 후처리와 나눠 쓰게 된다(r120 Diagnosis 2 B3)
// 그래서 컨슈머가 승인(또는 실패 기록) 트랜잭션을 커밋한 뒤에 이 신호를 세우고, confirm 은 신호만 읽다가 끝에 결제를 DB 에서 한 번 읽는다
// Redis 에 두므로 다른 인스턴스가 처리한 컨슈머의 결과도 보인다. 신호가 유실돼도 마지막 DB 읽기가 답을 정하므로 대기 시간만 늘 뿐 답이 틀리지 않는다
@Slf4j
@Component
public class ConfirmCompletionSignal {

    private static final String KEY_PREFIX = "payment:confirm:done:";

    private final StringRedisTemplate redis;

    // 대기 상한(payment.confirm.wait-timeout-ms)보다 길어야 한다 - 짧으면 오래 기다리는 confirm 이 끝나기 전에 신호가 사라져 상한까지 기다린다
    @Value("${payment.confirm.signal-ttl-ms:30000}")
    private long ttlMs = 30000;

    @Autowired
    public ConfirmCompletionSignal(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String keyOf(UUID paymentId) {
        return KEY_PREFIX + paymentId;
    }

    // 커밋 뒤에 부른다. 값은 신호를 세운 시각(epoch ms) - 기다린 confirm 이 커밋 뒤 얼마 만에 보았는지 잰다
    // Redis 오류는 삼킨다 - 이미 커밋된 승인을 되돌릴 수 없고, 신호가 없어도 confirm 은 대기 끝에 DB 를 읽어 답한다
    public void complete(UUID paymentId) {
        try {
            redis.opsForValue().set(keyOf(paymentId), String.valueOf(System.currentTimeMillis()), Duration.ofMillis(ttlMs));
        } catch (RuntimeException e) {
            log.warn("[CONFIRM_SIGNAL_SET_FAILED] 신호를 세우지 못함, 기다리는 confirm 은 대기 끝에 DB 로 확인 - PaymentId: {}, Error: {}",
                    paymentId, e.getMessage());
        }
    }

    // 신호가 서 있으면 세운 시각(epoch ms), 없으면 null. Redis 오류는 던진다 - 호출자가 DB 폴링으로 돌아간다
    public Long completedAt(UUID paymentId) {
        String value = redis.opsForValue().get(keyOf(paymentId));
        return value == null ? null : Long.valueOf(value);
    }
}
