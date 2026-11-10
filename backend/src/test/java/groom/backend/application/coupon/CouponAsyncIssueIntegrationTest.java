package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.coupon.CouponStreamConsumer;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 쿠폰 비동기 발급 통합 테스트 (실 PostgreSQL + Redis 필요)
 *
 * <p>접수(enqueue) → 스트림 → 컨슈머 한 번 읽기(pollCoupon) → 확정 흐름을 검증한다.
 * 컨슈머 루프의 타이밍에 의존하지 않도록 pollCoupon 을 직접 부른다.
 *
 * <p>판정은 접수 Lua 에 있고 확정 경로는 재고를 다시 깎지 않는다.
 * 어느 경로를 탔는지는 <b>Redis 재고값</b>으로 판별한다 - DB 수량은 아무도 고치지 않는 불변 발행 수량이다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 비동기 발급 통합 테스트")
class CouponAsyncIssueIntegrationTest {

    // DB 수량. Redis 재고는 이 값과 다른 수를 심어 두 저장소를 구분한다
    private static final long DB_QUANTITY = 100L;
    private static final long REDIS_STOCK = 5L;

    @Autowired
    private CouponAsyncIssueService couponAsyncIssueService;
    @Autowired
    private CouponStreamConsumer consumer;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CouponStockRedisRepository couponStockRedisRepository;

    private CouponStreamFixture fixture;
    private Long couponId;
    private Long userId;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        couponId = fixture.coupon(DB_QUANTITY);
        userId = fixture.user();
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    // 접수 응답의 requestId 는 픽스처 밖에서 생기므로 상태 키를 직접 지운다
    private String enqueueAccepted(Long targetUser) {
        CouponIssueResult result = couponAsyncIssueService.enqueue(couponId, targetUser);
        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.ACCEPTED);
        redis.expire(CouponStockRedisRepository.statusKey(result.requestId()), java.time.Duration.ofSeconds(60));
        return result.requestId();
    }

    @Test
    @DisplayName("재고 미워밍업 시 접수는 스트림에 싣지 않고 보류로 응답한다")
    void enqueue_미워밍업이면_싣지_않는다() {
        CouponIssueResult result = couponAsyncIssueService.enqueue(couponId, userId);

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.UNAVAILABLE);
        assertThat(redis.hasKey(CouponStockRedisRepository.streamKey(couponId))).isFalse();
        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isEmpty();
    }

    @Test
    @DisplayName("접수한 항목은 컨슈머가 확정만 하고 재고를 다시 깎지 않으며 DB 수량도 그대로다")
    void pollCoupon_접수분은_재고를_다시_깎지_않고_확정만_한다() {
        couponStockRedisRepository.initStock(couponId, REDIS_STOCK);
        String requestId = enqueueAccepted(userId);

        consumer.pollCoupon(couponId);

        assertThat(couponAsyncIssueService.getStatus(requestId)).isEqualTo("SUCCESS");
        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isPresent();
        // 접수 Lua 의 DECR 한 번만 있다. 확정 경로가 한 번 더 깎으면 REDIS_STOCK − 2 가 된다
        assertThat(couponStockRedisRepository.getStock(couponId)).isEqualTo(REDIS_STOCK - 1);
        assertThat(couponStockRedisRepository.getIssuedUsers(couponId)).contains(String.valueOf(userId));
        // quantity 는 불변 발행 수량이다. 확정 경로에 감산이 되살아나면 같은 쿠폰의 모든 확정이 한 행을 두고 줄을 선다
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(DB_QUANTITY);
        redis.delete(CouponStockRedisRepository.statusKey(requestId));
    }

    @Test
    @DisplayName("Redis 재고 소진 시 접수가 품절로 거부하고 스트림에 싣지 않는다")
    void enqueue_재고가_0이면_품절로_거부한다() {
        couponStockRedisRepository.initStock(couponId, 0L);

        assertThat(couponAsyncIssueService.enqueue(couponId, userId).status())
                .isEqualTo(CouponIssueResult.Status.SOLD_OUT);

        assertThat(redis.hasKey(CouponStockRedisRepository.streamKey(couponId))).isFalse();
        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isEmpty();
        assertThat(couponStockRedisRepository.getStock(couponId)).isZero();
    }

    @Test
    @DisplayName("같은 사용자의 두 번째 접수는 중복으로 거부되고 재고는 한 번만 깎인다")
    void enqueue_같은_사용자가_두_번_요청하면_중복으로_거부한다() {
        couponStockRedisRepository.initStock(couponId, REDIS_STOCK);
        String first = enqueueAccepted(userId);
        consumer.pollCoupon(couponId);

        assertThat(couponAsyncIssueService.enqueue(couponId, userId).status())
                .isEqualTo(CouponIssueResult.Status.ALREADY_ISSUED);

        assertThat(couponIssueRepository.findCouponIssueByUserId(userId)).hasSize(1);
        assertThat(couponStockRedisRepository.getStock(couponId)).isEqualTo(REDIS_STOCK - 1);
        redis.delete(CouponStockRedisRepository.statusKey(first));
    }

    @Test
    @DisplayName("재고가 0 이어도 이미 발급받은 사용자는 품절이 아니라 중복으로 거부된다")
    void enqueue_재고가0이어도_이미_발급받았으면_중복이다() {
        couponStockRedisRepository.initStock(couponId, 1L);
        String first = enqueueAccepted(userId);
        consumer.pollCoupon(couponId);
        assertThat(couponStockRedisRepository.getStock(couponId)).isZero();

        // 중복 검사가 재고 검사보다 앞서야 품절 뒤의 재요청이 중복으로 집계된다
        assertThat(couponAsyncIssueService.enqueue(couponId, userId).status())
                .isEqualTo(CouponIssueResult.Status.ALREADY_ISSUED);

        assertThat(couponStockRedisRepository.getStock(couponId)).isZero();
        redis.delete(CouponStockRedisRepository.statusKey(first));
    }

    @Test
    @DisplayName("발급 이력이 없는 사용자는 재고가 0 이면 그대로 품절로 거부된다")
    void enqueue_발급이력이_없으면_재고0은_품절이다() {
        couponStockRedisRepository.initStock(couponId, 0L);

        assertThat(couponAsyncIssueService.enqueue(couponId, userId).status())
                .isEqualTo(CouponIssueResult.Status.SOLD_OUT);
    }
}
