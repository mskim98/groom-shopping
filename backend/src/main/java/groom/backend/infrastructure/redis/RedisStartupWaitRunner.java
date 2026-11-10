package groom.backend.infrastructure.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

// Redis 를 쓰는 기동 러너(구 키 이관 · 쿠폰 워밍업 · 상품 재고 워밍업)보다 먼저 Redis 가 붙을 때까지 한 번 기다린다
//
// 같은 호스트에서 컨테이너가 한꺼번에 올라올 때 백엔드가 Redis 보다 먼저 떠 있으면 러너의 첫 명령이
// 연결 실패로 터져 기동이 멈춘다(r119 backend3). 러너마다 재시도를 넣지 않고 여기서 한 번만 기다린다
//
// 한도를 넘기면 던진다. 조용히 넘기면 러너가 Redis 없이 돌거나 건너뛰어 재고 키가 비어 첫 요청이 거절된다
// 기동이 멈추면 compose 의 restart 정책이 백엔드를 다시 띄운다
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class RedisStartupWaitRunner implements ApplicationRunner {

    // 재시도 간격은 두 배씩 늘리되 이 값을 넘기지 않는다
    private static final long MAX_BACKOFF_MS = 5_000;

    private final RedisConnectionFactory connectionFactory;
    private final long timeoutMs;
    private final long retryIntervalMs;

    public RedisStartupWaitRunner(RedisConnectionFactory connectionFactory,
                                  @Value("${redis.startup-wait.timeout-ms:60000}") long timeoutMs,
                                  @Value("${redis.startup-wait.retry-interval-ms:500}") long retryIntervalMs) {
        this.connectionFactory = connectionFactory;
        this.timeoutMs = timeoutMs;
        this.retryIntervalMs = retryIntervalMs;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        long backoff = retryIntervalMs;
        int attempts = 0;
        while (true) {
            attempts++;
            try (RedisConnection connection = connectionFactory.getConnection()) {
                connection.ping();
                if (attempts > 1) {
                    log.info("[REDIS_STARTUP_WAIT] Redis 연결 확인 - {}번째 시도", attempts);
                }
                return;
            } catch (RuntimeException e) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    throw new IllegalStateException("Redis 에 " + timeoutMs + "ms 안에 연결하지 못해 기동 러너"
                            + "(CouponRedisKeyMigrationRunner · CouponStockWarmUpRunner · StockWarmUpRunner)를 "
                            + "시작하지 않고 기동을 멈춘다 - " + attempts + "번 시도, 마지막 오류: " + e.getMessage(), e);
                }
                log.warn("[REDIS_STARTUP_WAIT] Redis 가 아직 응답하지 않는다 - {}번째 시도, {}ms 뒤 다시: {}",
                        attempts, Math.min(backoff, remainingMs), e.getMessage());
                Thread.sleep(Math.min(backoff, remainingMs));
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }
}
