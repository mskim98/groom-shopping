package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.infrastructure.redis.RedisStartupWaitRunner;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 기동 러너가 Redis 연결 전에 죽지 않는지 (실 PostgreSQL + Redis 필요)
 *
 * <p>r119 에서 backend3 가 Redis 연결 전에 구 키 이관 러너의 SCAN 에서 죽어 내려갔다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("기동 시 Redis 대기")
class CouponRedisKeyMigrationRunnerStartupTest {

    @Autowired
    private RedisConnectionFactory realFactory;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CouponRedisKeyMigrationRunner migrationRunner;
    @Autowired
    private List<ApplicationRunner> runners;

    @Test
    @DisplayName("처음 두 번 연결이 실패해도 한도 안에 붙으면 대기가 끝나고 구 키를 새 키로 옮긴다")
    void run_처음_연결이_실패해도_한도_안에_붙으면_이관이_된다() throws Exception {
        long couponId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        String legacyKey = "coupon:stock:" + couponId;
        String newKey = "coupon:{" + couponId + "}:stock";
        redis.opsForValue().set(legacyKey, "7");
        try {
            RedisConnectionFactory flaky = mock(RedisConnectionFactory.class);
            RedisConnectionFailureException down = new RedisConnectionFailureException("Redis 아직 안 붙음");
            when(flaky.getConnection()).thenThrow(down).thenThrow(down)
                    .thenAnswer(inv -> realFactory.getConnection());

            new RedisStartupWaitRunner(flaky, 1_000, 10).run(null);
            migrationRunner.run(null);

            verify(flaky, times(3)).getConnection();
            assertThat(redis.opsForValue().get(newKey)).isEqualTo("7");
            assertThat(redis.hasKey(legacyKey)).isFalse();
        } finally {
            redis.delete(List.of(legacyKey, newKey));
        }
    }

    @Test
    @DisplayName("한도 안에 Redis 가 붙지 않으면 Redis 와 기동 러너를 이름에 넣은 예외로 기동을 멈춘다")
    void run_한도를_넘기면_Redis와_러너를_말하는_예외를_던진다() {
        RedisConnectionFactory dead = mock(RedisConnectionFactory.class);
        when(dead.getConnection()).thenThrow(new RedisConnectionFailureException("연결 거부"));

        assertThatThrownBy(() -> new RedisStartupWaitRunner(dead, 150, 10).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis")
                .hasMessageContaining("CouponRedisKeyMigrationRunner")
                .hasMessageContaining("CouponStockWarmUpRunner")
                .hasMessageContaining("StockWarmUpRunner");
        verify(dead, atLeast(2)).getConnection();
    }

    @Test
    @DisplayName("PING 명령이 실패해도 한도 안에 되살아나면 대기가 끝난다")
    void run_PING이_실패해도_되살아나면_끝난다() throws Exception {
        RedisConnection conn = mock(RedisConnection.class);
        when(conn.ping()).thenThrow(new QueryTimeoutException("ping 시간 초과")).thenReturn("PONG");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(conn);

        new RedisStartupWaitRunner(factory, 1_000, 10).run(null);

        verify(conn, times(2)).ping();
    }

    @Test
    @DisplayName("대기 러너는 Redis 를 쓰는 세 러너보다 모두 앞서 돈다")
    void order_대기가_세_러너보다_앞선다() {
        int wait = indexOf(RedisStartupWaitRunner.class);
        for (Class<?> runner : List.of(CouponRedisKeyMigrationRunner.class,
                CouponStockWarmUpRunner.class, StockWarmUpRunner.class)) {
            assertThat(wait).as("대기 러너 위치 < %s 위치", runner.getSimpleName())
                    .isLessThan(indexOf(runner));
        }
        assertThat(OrderUtils.getOrder(RedisStartupWaitRunner.class, Ordered.LOWEST_PRECEDENCE))
                .isLessThan(OrderUtils.getOrder(CouponRedisKeyMigrationRunner.class, Ordered.LOWEST_PRECEDENCE));
    }

    // 기동 때 스프링이 정렬해 주입한 러너 목록에서 클래스의 위치
    private int indexOf(Class<?> type) {
        for (int i = 0; i < runners.size(); i++) {
            if (type.isInstance(runners.get(i))) {
                return i;
            }
        }
        throw new AssertionError(type.getSimpleName() + " 이 러너 목록에 없다");
    }
}
