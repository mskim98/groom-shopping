package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 읽기 경로가 NOGROUP 을 스스로 고친다 - 유휴 항목 스캔(claim-interval-ms 3600000 으로 사실상 끔)이 그룹을 되살릴 수 없을 때도
 * 감시 쿠폰의 스트림 키가 사라졌다 다시 생기면 같은 순회 안에서 그룹을 다시 만들어 읽는다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "coupon.issue.stream.claim-interval-ms=3600000"
})
@DisplayName("쿠폰 스트림 NOGROUP 복구")
class CouponStreamNoGroupRecoveryTest {

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

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    @Test
    @DisplayName("감시 쿠폰의 스트림 키가 지워졌다 접수로 다시 생기면 pollOnce 두 번 안에 행이 쓰이고 SUCCESS 이며 던지지 않는다")
    void pollOnce_그룹이_사라진_뒤에도_두번_안에_발급하고_던지지_않는다() {
        Long couponId = fixture.coupon(10);
        stockRepository.initStock(couponId, 10L);
        // 첫 순회가 감시 목록에 쿠폰을 넣고 그룹을 만들어 준비된 그룹 캐시에 담는다
        consumer.pollOnce();
        // Redis 가 비워진 상황: 스트림 키(그룹 포함)가 사라지고 캐시만 그룹이 있다고 믿는다
        redis.delete(CouponStockRedisRepository.streamKey(couponId));
        Long userId = fixture.user();
        String requestId = fixture.requestId();
        stockRepository.enqueue(couponId, userId, requestId);

        assertThatNoException().isThrownBy(consumer::pollOnce);
        if (couponIssueRepository.countByCoupon_Id(couponId) == 0L) {
            assertThatNoException().isThrownBy(consumer::pollOnce);
        }

        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        assertThat(fixture.status(requestId)).isEqualTo("SUCCESS");
    }
}
