package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoConsumer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 유휴 스캔 중 오래 쉰 다른 컨슈머 이름을 지운다(XGROUP DELCONSUMER). 지우지 않으면 재기동마다 새 이름이 생겨 그룹의 컨슈머 목록이 끝없이 자란다
 *
 * <p>한도를 2 초로 두고 오래 쉰 이름은 2.3 초 넘게 기다린 뒤, 덜 쉰 이름은 스캔 직전에 만든다 - 시간 창이 200 ms 처럼 좁으면 기계가 느릴 때 판정이 뒤집힌다
 * claim-min-idle-ms 를 60000 으로 올려 대기 항목을 쥔 컨슈머의 항목을 스캔이 가져가지 않게 한다 - 가져가면 그 컨슈머의 대기 수가 0 이 되어 지울 대상이 된다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "coupon.issue.stream.claim-min-idle-ms=60000",
        "coupon.issue.stream.claim-interval-ms=100",
        "coupon.issue.stream.consumer-retire-idle-ms=2000"
})
@DisplayName("쿠폰 스트림 오래 쉰 컨슈머 정리")
class CouponStreamConsumerRetireTest {

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

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        couponId = fixture.coupon(10);
        stockRepository.initStock(couponId, 10L);
        consumer.ensureGroup(couponId);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    private List<String> consumerNames() {
        return redis.opsForStream().consumers(CouponStockRedisRepository.streamKey(couponId), CouponStreamConsumer.GROUP)
                .stream().map(XInfoConsumer::consumerName).toList();
    }

    private long idleOf(String name) {
        return redis.opsForStream().consumers(CouponStockRedisRepository.streamKey(couponId), CouponStreamConsumer.GROUP)
                .stream().filter(c -> c.consumerName().equals(name)).mapToLong(XInfoConsumer::idleTimeMs).findFirst().orElse(-1L);
    }

    @Test
    @DisplayName("대기 0 이고 한도 이상 쉰 다른 컨슈머만 지워지고 대기 항목이 있는 컨슈머 · 덜 쉰 컨슈머 · 자기 자신은 남는다")
    void pollCoupon_스캔중_대기없이_오래_쉰_다른_컨슈머만_지운다() {
        // 대기 0 인 컨슈머 - 읽을 항목 없이 이름만 생긴다
        fixture.readAs(couponId, "retire-idle");
        // 대기 1 인 컨슈머
        stockRepository.enqueue(couponId, fixture.user(), fixture.requestId());
        assertThat(fixture.readAs(couponId, "retire-pending")).hasSize(1);
        // 이 컨슈머 자신이 먼저 이름을 만들고, 오래 쉰 이름들이 한도(2 초)를 넘길 때까지 기다린다
        consumer.pollCoupon(couponId);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> idleOf("retire-idle") >= 2_300 && idleOf("retire-pending") >= 2_300 && idleOf(consumer.consumerName()) >= 2_300);
        // 덜 쉰 컨슈머는 스캔 직전에 만든다
        fixture.readAs(couponId, "retire-fresh");
        assertThat(consumerNames()).contains("retire-idle", "retire-pending", "retire-fresh", consumer.consumerName());

        consumer.pollCoupon(couponId);

        assertThat(consumerNames()).doesNotContain("retire-idle")
                .contains("retire-pending", "retire-fresh", consumer.consumerName());
    }
}
