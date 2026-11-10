package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import groom.backend.application.coupon.CouponIssueBatchWriter;
import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 배달 횟수가 max-deliveries(3) 를 넘은 독 항목은 처리하지 않고 DLQ 로 보낸다
 *
 * <p>항목은 배달 1 ~ 3 회째까지 처리를 시도한다. 4 회째 인수에서 횟수가 3 을 넘으므로 DLQ 다.
 * 읽기 1 회 + XCLAIM 을 더한 횟수로 만들고, 시험 설정 claim-min-idle-ms = 200 이 지나기를 기다린다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 스트림 독 항목 DLQ")
class CouponStreamDlqTest {

    @Autowired
    private CouponStreamConsumer consumer;
    @Autowired
    private CouponIssueBatchWriter writer;
    @Autowired
    private CouponStockRedisRepository stockRepository;
    @Autowired
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
    private String entryId;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        couponId = fixture.coupon(10);
        stockRepository.initStock(couponId, 10L);
        userId = fixture.user();
        requestId = fixture.requestId();
        stockRepository.enqueue(couponId, userId, requestId);
        consumer.ensureGroup(couponId);
        entryId = fixture.readAs(couponId, "dead-a").get(0);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    // 읽기 1 회를 포함해 배달 횟수를 target 으로 맞춘다
    private void deliverUntil(int target) {
        for (int delivered = 1; delivered < target; delivered++) {
            fixture.claimAs(couponId, "dead-b", entryId);
        }
    }

    private void pollAfterIdle() {
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            consumer.pollCoupon(couponId);
            assertThat(fixture.pendingCount(couponId)).isZero();
        });
    }

    @Test
    @DisplayName("배달 횟수가 3 을 넘으면 DLQ 에 원본 필드와 사유를 쓰고 ack 하며 예약을 되돌리고 FAILED 로 남긴다")
    void pollCoupon_횟수를_넘으면_dlq로_보내고_예약을_되돌린다() {
        deliverUntil(3);

        pollAfterIdle();

        List<MapRecord<String, Object, Object>> dlq =
                redis.opsForStream().range(CouponStockRedisRepository.dlqKey(couponId), Range.unbounded());
        assertThat(dlq).hasSize(1);
        assertThat(dlq.get(0).getValue())
                .containsEntry("userId", String.valueOf(userId))
                .containsEntry("requestId", requestId)
                .containsKey("reason");
        assertThat(fixture.pendingCount(couponId)).isZero();
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isZero();
        assertThat(stockRepository.getStock(couponId)).isEqualTo(10L);
        assertThat(stockRepository.getIssuedUsers(couponId)).doesNotContain(String.valueOf(userId));
        assertThat(fixture.status(requestId)).startsWith("FAILED:");
    }

    @Test
    @DisplayName("배달 횟수가 2 이면 4 회째가 아니므로 DLQ 가 아니라 정상 처리된다")
    void pollCoupon_횟수가_한도_안이면_처리한다() {
        deliverUntil(2);

        pollAfterIdle();

        assertThat(redis.hasKey(CouponStockRedisRepository.dlqKey(couponId))).isFalse();
        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        assertThat(fixture.status(requestId)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("횟수를 넘겼어도 행이 이미 커밋된 요청은 SUCCESS 로 마감하고 예약을 되돌리지 않는다")
    void pollCoupon_행이_이미_있으면_dlq가_아니라_성공으로_마감한다() {
        // 커밋은 됐는데 ack 가 계속 실패한 항목이다
        writer.insertAll(couponId, Map.of(userId, requestId));
        deliverUntil(3);

        pollAfterIdle();

        assertThat(redis.hasKey(CouponStockRedisRepository.dlqKey(couponId))).isFalse();
        assertThat(fixture.status(requestId)).isEqualTo("SUCCESS");
        // 되돌리면 DB 에는 행이 있는데 재고만 늘어난다
        assertThat(stockRepository.getStock(couponId)).isEqualTo(9L);
        assertThat(stockRepository.getIssuedUsers(couponId)).contains(String.valueOf(userId));
    }
}
