package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.application.coupon.strategy.DbLockIssueStrategy;
import groom.backend.domain.coupon.repository.CouponRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 접수 경로의 Redis 오류 처리 (Redis 는 가짜)
 *
 * <p>접수 스크립트의 연결 실패 · 시간 초과는 503 으로 바뀌지 않고 DB 경로로 넘어간다.
 * 연속 실패가 임계를 넘으면 {@code redis-coupon} 회로가 열려 이후 요청은 Redis 를 부르지 않는다
 */
@DisplayName("쿠폰 접수 Redis 장애")
class CouponAcceptRedisFailureTest {

    private final CouponRepository couponRepository = mock(CouponRepository.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    private final CouponStockRedisRepository stockRepository = mock(CouponStockRedisRepository.class);
    private final CouponStockRecoveryService recovery = mock(CouponStockRecoveryService.class);
    private final DbLockIssueStrategy dbLock = mock(DbLockIssueStrategy.class);

    private CouponRedisCircuit circuit;
    private CouponAsyncIssueService service;

    @BeforeEach
    void setUp() {
        // 최소 호출 수 2 · 실패율 50% - 두 번 실패하면 열린다
        circuit = new CouponRedisCircuit(CircuitBreakerRegistry.ofDefaults(), 50f, 2, 2, 60_000L);
        service = new CouponAsyncIssueService(couponRepository, redisTemplate, stockRepository, recovery, dbLock,
                circuit, mock(MyCouponCacheEvictor.class));
        given(dbLock.issueFor(anyLong(), anyLong())).willReturn(CouponIssueResult.success(1L));
    }

    static Stream<RuntimeException> redisFailures() {
        return Stream.of(
                new RedisConnectionFailureException("Unable to connect to Redis"),
                new QueryTimeoutException("Redis command timed out"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("redisFailures")
    @DisplayName("접수 스크립트의 Redis 오류는 503 이 아니라 DB 경로 발급으로 이어진다")
    void enqueue_Redis오류는_DB경로로_넘어간다(RuntimeException failure) {
        given(stockRepository.enqueue(eq(1L), eq(100L),
                anyString())).willThrow(failure);

        CouponIssueResult result = service.enqueue(1L, 100L);

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        verify(dbLock).issueFor(1L, 100L);
    }

    @Test
    @DisplayName("연속 실패로 회로가 열리면 이후 요청은 Redis 를 부르지 않고 DB 경로로 간다")
    void enqueue_회로가_열리면_redis를_건너뛴다() {
        given(stockRepository.enqueue(eq(1L), eq(100L),
                anyString())).willThrow(new RedisConnectionFailureException("down"));

        service.enqueue(1L, 100L);
        service.enqueue(1L, 100L);
        assertThat(circuit.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        service.enqueue(1L, 100L);

        // 세 번째 요청은 Redis 를 부르지 않았다 - 호출은 앞의 두 번뿐이다
        verify(stockRepository, times(2)).enqueue(eq(1L),
                eq(100L), anyString());
        verify(dbLock, times(3)).issueFor(1L, 100L);
    }
}
