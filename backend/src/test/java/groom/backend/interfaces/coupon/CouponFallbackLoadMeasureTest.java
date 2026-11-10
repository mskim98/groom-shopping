package groom.backend.interfaces.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.coupon.CouponStreamConsumer;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 스트림 발급 부하 실측 - 초당 2,000 건 · 총 10,000 건
 *
 * <p>접수 진입점에 도착률로 요청을 넣고 컨슈머 루프가 스트림을 비우는 시간과 발급 수를 잰다.
 * 합격선은 두 가지다 - 도착률이 목표의 ±5% 안이고, 발급 수가 발행 수량과 같다
 *
 * <p>이 클래스는 쿠폰 시험 묶음({@code groom.backend.application.coupon.*})에 들지 않도록 이 패키지에 둔다 -
 * 환경 변수가 없으면 건너뛴 결과 파일이 남아 묶음 판정이 건너뜀 수 때문에 실패한다
 *
 * <p>평소 테스트 실행에서는 돌지 않는다. 사용자 1 만 명을 만들고 수십 초가 걸린다
 * {@code MEASURE_FALLBACK_LOAD=1 ./gradlew test --rerun --tests '*CouponFallbackLoadMeasureTest'}
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "MEASURE_FALLBACK_LOAD", matches = "1")
@DisplayName("쿠폰 스트림 부하 실측")
class CouponFallbackLoadMeasureTest {

    private static final int TOTAL = 10_000;
    private static final int RATE_PER_SEC = 2_000;
    // 이벤트 수의 절반 - 발급과 품절 두 갈래를 모두 지나야 초과 발급 여부가 드러난다
    private static final long QUANTITY = 5_000L;
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(120);

    @Autowired
    private CouponAsyncIssueService couponAsyncIssueService;
    @Autowired
    private CouponStreamConsumer consumer;
    @Autowired
    private CouponStockRedisRepository couponStockRedisRepository;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;

    private CouponStreamFixture fixture;

    @AfterEach
    void tearDown() {
        consumer.stop();
        if (fixture != null) {
            fixture.cleanup();
        }
    }

    @Test
    @DisplayName("초당 2,000건 · 총 1만 건을 접수해도 발급 수는 발행 수량과 같다")
    void 초당_2000건_총_1만건() throws Exception {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        Long couponId = fixture.coupon(QUANTITY);
        List<Long> userIds = fixture.users(TOTAL);
        couponStockRedisRepository.initStock(couponId, QUANTITY);
        consumer.start();

        List<String> accepted = new ArrayList<>();
        Map<String, Long> outcomes = new TreeMap<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < TOTAL; i++) {
            long wait = t0 + (long) i * 1_000_000_000L / RATE_PER_SEC - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
            CouponIssueResult result = couponAsyncIssueService.enqueue(couponId, userIds.get(i));
            outcomes.merge(result.status().name(), 1L, Long::sum);
            if (result.requestId() != null) {
                accepted.add(result.requestId());
            }
        }
        double arrivalSec = (System.nanoTime() - t0) / 1e9;

        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT.toMillis();
        while (couponStockRedisRepository.streamLength(couponId) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        double drainSec = (System.nanoTime() - t0) / 1e9;
        long issued = couponIssueRepository.countByCoupon_Id(couponId);
        long succeeded = accepted.stream()
                .filter(id -> "SUCCESS".equals(couponAsyncIssueService.getStatus(id))).count();
        System.out.printf("[STREAM_LOAD] total=%d rate=%.0f/s arrivalSec=%.2f drainSec=%.2f issued=%d "
                        + "succeeded=%d redisStock=%s outcomes=%s%n",
                TOTAL, TOTAL / arrivalSec, arrivalSec, drainSec, issued, succeeded,
                couponStockRedisRepository.getStock(couponId), outcomes);
        redis.delete(accepted.stream().map(CouponStockRedisRepository::statusKey).toList());

        assertThat(TOTAL / arrivalSec).isBetween(RATE_PER_SEC * 0.95, RATE_PER_SEC * 1.05);
        assertThat(issued).isEqualTo(QUANTITY);
        assertThat(succeeded).isEqualTo(QUANTITY);
        assertThat(outcomes.get("ACCEPTED")).isEqualTo(QUANTITY);
    }
}
