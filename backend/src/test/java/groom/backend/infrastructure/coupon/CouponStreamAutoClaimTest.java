package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 죽은 인스턴스가 쥔 항목은 유휴 시간이 지나면 다른 인스턴스가 가져와 처리한다
 *
 * <p>시험 설정 claim-min-idle-ms = 200 (test application.properties). 운영 기본값은 30000 이다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 스트림 유휴 항목 인수")
class CouponStreamAutoClaimTest {

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
    private Long couponId;
    private Long userId;
    private String requestId;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        couponId = fixture.coupon(10);
        stockRepository.initStock(couponId, 10L);
        userId = fixture.user();
        requestId = fixture.requestId();
        stockRepository.enqueue(couponId, userId, requestId);
        consumer.ensureGroup(couponId);
        // 죽은 인스턴스가 읽기만 하고 처리하지 못했다
        fixture.readAs(couponId, "dead-instance-1");
    }

    @AfterEach
    void tearDown() {
        reset(redis);
        fixture.cleanup();
    }

    private String ownerOfPending() {
        PendingMessages pending = redis.opsForStream().pending(CouponStockRedisRepository.streamKey(couponId),
                CouponStreamConsumer.GROUP, org.springframework.data.domain.Range.unbounded(), 10);
        return pending.isEmpty() ? null : pending.get(0).getConsumerName();
    }

    @Test
    @DisplayName("유휴 시간이 지난 항목은 가져와 처리하고 지나지 않은 항목은 주인에게 둔다")
    void pollCoupon_유휴가_지난_항목만_가져온다() {
        // 읽은 지 얼마 안 됐다 - 주인이 처리 중일 수 있으므로 건드리지 않는다
        consumer.pollCoupon(couponId);
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isZero();
        assertThat(ownerOfPending()).isEqualTo("dead-instance-1");

        // 유휴 200ms 를 넘긴다
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            consumer.pollCoupon(couponId);
            assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(1L);
        });

        assertThat(fixture.status(requestId)).isEqualTo("SUCCESS");
        assertThat(fixture.pendingCount(couponId)).isZero();
    }

    @Test
    @DisplayName("살아 있는 컨슈머의 유휴 전 항목이 batch-size 를 넘게 앞서 있어도 뒤의 유휴 항목을 한 번에 가져온다")
    void pollCoupon_앞에_바쁜_항목이_많아도_유휴_항목을_한번에_가져온다() {
        Long busyCoupon = fixture.coupon(2000);
        stockRepository.initStock(busyCoupon, 2000L);
        consumer.ensureGroup(busyCoupon);
        // batch-size(500) + 1 건은 접수만 한 가짜 사용자 항목이다. 마지막 1건만 실제 사용자 항목이다
        for (int i = 0; i < 501; i++) {
            stockRepository.enqueue(busyCoupon, 9_000_000_000L + i, fixture.requestId());
        }
        Long realUser = fixture.user();
        String realRequestId = fixture.requestId();
        stockRepository.enqueue(busyCoupon, realUser, realRequestId);
        // 전부 죽은 인스턴스가 읽은 뒤 유휴 한도를 넘긴다
        List<String> ids = fixture.readAs(busyCoupon, "dead-instance-2");
        assertThat(ids).hasSize(502);
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).until(() -> true);
        // 앞의 501 건만 살아 있는 컨슈머가 방금 쥐었다(유휴 0). 마지막 1건은 여전히 유휴 한도를 넘었다
        fixture.claimAllAs(busyCoupon, "live-instance", ids.subList(0, 501));

        int handled = consumer.pollCoupon(busyCoupon);

        assertThat(handled).isEqualTo(1);
        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(busyCoupon, realUser)).isEqualTo(1L);
        assertThat(fixture.status(realRequestId)).isEqualTo("SUCCESS");
        // 살아 있는 컨슈머의 항목은 건드리지 않았다
        assertThat(fixture.pendingCount(busyCoupon)).isEqualTo(501L);
    }

    @Test
    @DisplayName("연속 두 번 부르면 유휴 항목 스캔은 한 번만 돌고 간격이 지나면 다시 돈다")
    void pollCoupon_연속_두번이면_스캔은_한번이다() {
        long before = consumer.claimScans();

        consumer.pollCoupon(couponId);
        consumer.pollCoupon(couponId);

        // 시험 설정 claim-min-idle-ms = 200 이므로 기본 스캔 간격은 그 절반(100ms)이다
        assertThat(consumer.claimScans() - before).isEqualTo(1L);
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            consumer.pollCoupon(couponId);
            assertThat(consumer.claimScans() - before).isEqualTo(2L);
        });
    }

    @Test
    @DisplayName("XAUTOCLAIM 패스가 던지면 스캔 시각을 남기지 않아 다음 호출이 간격을 기다리지 않고 그 쿠폰을 다시 스캔한다")
    void pollCoupon_패스가_던지면_다음_호출이_간격을_기다리지_않고_다시_스캔한다() throws Exception {
        ReflectionTestUtils.setField(consumer, "claimIntervalMs", 3_600_000L);
        try {
            Thread.sleep(300);
            doThrow(new IllegalStateException("redis down")).doCallRealMethod().when(redis).execute(any(RedisCallback.class));

            assertThatThrownBy(() -> consumer.pollCoupon(couponId)).isInstanceOf(IllegalStateException.class);
            assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isZero();

            consumer.pollCoupon(couponId);

            assertThat(couponIssueRepository.countByCoupon_Id(couponId)).as("실패한 패스 직후의 스캔이 항목을 가져와 처리했다").isEqualTo(1L);
            assertThat(fixture.status(requestId)).isEqualTo("SUCCESS");
        } finally {
            ReflectionTestUtils.setField(consumer, "claimIntervalMs", 0L);
        }
    }

    @Test
    @DisplayName("한 스캔이 가져온 항목 가운데 한도를 넘긴 것만 DLQ 로 가고 나머지는 처리되며 사이에 낀 살아 있는 컨슈머의 항목은 그대로다")
    void pollCoupon_가져온_항목의_배달횟수만_보고_한도를_넘긴_것만_dlq로_보낸다() {
        String liveRequestId = fixture.requestId();
        Long liveUser = fixture.user();
        stockRepository.enqueue(couponId, liveUser, liveRequestId);
        Long freshUser = fixture.user();
        String freshRequestId = fixture.requestId();
        stockRepository.enqueue(couponId, freshUser, freshRequestId);
        // setUp 에서 죽은 컨슈머가 읽은 것은 첫 항목. 나머지 둘은 같은 컨슈머가 이어서 읽는다
        List<String> rest = fixture.readAs(couponId, "dead-instance-1");
        assertThat(rest).hasSize(2);
        // 첫 항목은 배달 횟수를 한도(3) 위로 올린다
        String poisonId = ownerEntryIds().get(0);
        for (int i = 0; i < 3; i++) {
            fixture.claimAs(couponId, "dead-instance-1", poisonId);
        }
        // 둘째 항목(살아 있는 컨슈머가 쥔 것)은 유휴를 0 으로 만들어 이번 스캔이 건드리지 않게 한다
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).until(() -> true);
        fixture.claimAllAs(couponId, "live-instance", List.of(rest.get(0)));

        int handled = consumer.pollCoupon(couponId);

        assertThat(handled).isEqualTo(2);
        assertThat(redis.opsForStream().size(CouponStockRedisRepository.dlqKey(couponId))).isEqualTo(1L);
        assertThat(fixture.status(requestId)).startsWith("FAILED:");
        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, freshUser)).isEqualTo(1L);
        assertThat(fixture.status(freshRequestId)).isEqualTo("SUCCESS");
        assertThat(fixture.pendingCount(couponId)).as("살아 있는 컨슈머의 항목만 남는다").isEqualTo(1L);
    }

    @Test
    @DisplayName("가져온 항목이 여럿이어도 배달 횟수 조회는 한 번의 파이프라인(왕복 한 번)이고 결과는 항목별로 그대로다")
    void pollCoupon_가져온_배치의_배달횟수는_한번의_파이프라인으로_읽는다() {
        for (int i = 0; i < 2; i++) {
            stockRepository.enqueue(couponId, fixture.user(), fixture.requestId());
        }
        // 죽은 컨슈머가 둘을 더 읽는다. 첫 항목은 배달 횟수를 한도(3) 위로 올린다
        assertThat(fixture.readAs(couponId, "dead-instance-1")).hasSize(2);
        String poisonId = ownerEntryIds().get(0);
        for (int i = 0; i < 3; i++) {
            fixture.claimAs(couponId, "dead-instance-1", poisonId);
        }
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).until(() -> true);
        clearInvocations(redis);

        int handled = consumer.pollCoupon(couponId);

        verify(redis, times(1)).executePipelined(any(RedisCallback.class));
        assertThat(handled).isEqualTo(3);
        assertThat(redis.opsForStream().size(CouponStockRedisRepository.dlqKey(couponId))).as("한도를 넘긴 항목만 DLQ").isEqualTo(1L);
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(2L);
    }

    private List<String> ownerEntryIds() {
        return redis.opsForStream().pending(CouponStockRedisRepository.streamKey(couponId), CouponStreamConsumer.GROUP,
                org.springframework.data.domain.Range.unbounded(), 10).stream()
                .map(m -> m.getIdAsString()).sorted().toList();
    }

    @Test
    @DisplayName("컨슈머 이름은 호스트 이름과 프로세스 번호로 만든다")
    void consumerName_호스트와_pid로_만든다() {
        assertThat(consumer.consumerName()).endsWith("-" + ProcessHandle.current().pid());
        assertThat(consumer.consumerName()).isNotEqualTo("-" + ProcessHandle.current().pid());
    }
}
