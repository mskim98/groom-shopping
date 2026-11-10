package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 유휴 항목을 가져온 뒤 배달 횟수를 파이프라인으로 읽는 순간 컨슈머 그룹이 사라져도(Redis 비움) 스캔이 던지지 않고 그룹을 되살려
 * 가져온 항목을 처리한다. 단건 호출은 withGroup 이 NOGROUP 을 고치지만 파이프라인 안의 NOGROUP 이 같은 모양으로 올라오는지를 본다
 *
 * <p>시험 설정 claim-min-idle-ms = 200 (test application.properties) - 스캔 간격은 그 절반이다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 스트림 배달 횟수 조회 NOGROUP 복구")
class CouponStreamDeliveryCountsNoGroupTest {

    @Autowired
    private CouponStreamConsumer consumer;
    @Autowired
    private CouponStockRedisRepository stockRepository;
    @SpyBean
    private StringRedisTemplate redis;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;

    private CouponStreamFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
    }

    @AfterEach
    void tearDown() {
        reset(redis);
        fixture.cleanup();
    }

    @Test
    @DisplayName("배달 횟수 파이프라인이 나가는 순간 그룹이 없어도 pollOnce 는 던지지 않고 그룹이 다시 생기며 항목은 두 번 안에 SUCCESS 로 한 행만 쓰인다")
    void pollOnce_배달횟수_조회_중_그룹이_사라져도_두번_안에_한_행으로_발급한다() {
        Long couponId = fixture.coupon(10);
        stockRepository.initStock(couponId, 10L);
        // 첫 순회가 감시 목록에 쿠폰을 넣고 그룹을 만든다
        consumer.pollOnce();
        Long userId = fixture.user();
        String requestId = fixture.requestId();
        stockRepository.enqueue(couponId, userId, requestId);
        // 죽은 인스턴스가 읽기만 하고 처리하지 못한 채 유휴 한도(200ms)를 넘긴다
        fixture.readAs(couponId, "dead-instance-1");
        CouponStreamWaits.awaitPendingIdle(redis, couponId, Duration.ofMillis(250));
        // 항목을 가져온 직후 배달 횟수 파이프라인이 나가는 순간에 그룹(대기 목록 포함)을 지운다
        AtomicBoolean destroyed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (destroyed.compareAndSet(false, true)) {
                redis.opsForStream().destroyGroup(CouponStockRedisRepository.streamKey(couponId), CouponStreamConsumer.GROUP);
            }
            return invocation.callRealMethod();
        }).when(redis).executePipelined(any(RedisCallback.class));

        assertThatNoException().isThrownBy(consumer::pollOnce);
        for (int i = 0; i < 2 && couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId) == 0L; i++) {
            assertThatNoException().isThrownBy(consumer::pollOnce);
        }

        assertThat(destroyed).as("파이프라인 직전에 그룹을 지웠다").isTrue();
        assertThat(redis.opsForStream().groups(CouponStockRedisRepository.streamKey(couponId)).stream()
                .anyMatch(group -> CouponStreamConsumer.GROUP.equals(group.groupName()))).as("그룹이 다시 생겼다").isTrue();
        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        assertThat(fixture.status(requestId)).isEqualTo("SUCCESS");
    }
}
