package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;

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
    @DisplayName("컨슈머 이름은 호스트 이름과 프로세스 번호로 만든다")
    void consumerName_호스트와_pid로_만든다() {
        assertThat(consumer.consumerName()).endsWith("-" + ProcessHandle.current().pid());
        assertThat(consumer.consumerName()).isNotEqualTo("-" + ProcessHandle.current().pid());
    }
}
