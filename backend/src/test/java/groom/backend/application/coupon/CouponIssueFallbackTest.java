package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.application.coupon.strategy.StreamIssueStrategy;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.coupon.CouponStreamConsumer;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 대기가 실패하면 요청 스레드가 자기 행을 직접 넣어 마무리하는지 본다 (실 PostgreSQL + Redis 필요)
 *
 * <p>컨슈머는 켜지 않는다 - 컨슈머가 멈춘 상태가 이 경로의 조건이다. 대기 상한은 800ms 로 줄여 둔다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "coupon.issue.wait-timeout-ms=800",
        "coupon.issue.wait-recheck-ms=300"
})
@DisplayName("쿠폰 발급 대기 실패 - 요청 스레드가 직접 마무리")
class CouponIssueFallbackTest {

    @Autowired
    private StreamIssueStrategy strategy;
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
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private CouponIssueWaiters waiters;
    @MockitoSpyBean
    private CouponAsyncIssueService asyncService; // 대기 중 Redis 읽기 장애를 만든다
    @MockitoSpyBean
    private CouponIssueBatchWriter writer; // fallback 의 DB 장애를 만든다

    private CouponStreamFixture fixture;
    private Long couponId;
    private Long userId;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        couponId = fixture.coupon(10);
        stockRepository.initStock(couponId, 10L);
        userId = fixture.user();
    }

    @AfterEach
    void tearDown() {
        reset(asyncService, writer);
        fixture.cleanup();
    }

    private User requester() {
        return new User(userId, "fallback-" + userId + "@test.com", "pw", "대체시험", Role.ROLE_USER, Grade.BRONZE, null, null);
    }

    private double fallbacks() {
        return meters.counter("coupon.issue.wake", "source", "fallback").count();
    }

    // 접수가 스트림에 실은 항목의 requestId. 이 쿠폰의 항목은 하나뿐이다
    private String streamedRequestId() {
        var entries = redis.opsForStream().range(CouponStockRedisRepository.streamKey(couponId), Range.unbounded());
        assertThat(entries).hasSize(1);
        return String.valueOf(entries.get(0).getValue().get("requestId"));
    }

    @Test
    @DisplayName("컨슈머가 멈춘 채 대기 상한이 지나면 요청 스레드가 행을 넣어 SUCCESS 로 답하고 상태 키를 SUCCESS 로 둔다")
    void issue_대기_상한이_지나면_요청_스레드가_행을_넣고_SUCCESS다() {
        double before = fallbacks();

        CouponIssueResult result = strategy.issue(couponId, requester());

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        assertThat(result.couponIssueId())
                .isEqualTo(couponIssueRepository.findByCouponIdAndUserId(couponId, userId).orElseThrow().getId());
        assertThat(fixture.status(streamedRequestId())).isEqualTo("SUCCESS");
        assertThat(fallbacks() - before).as("fallback 으로 끝난 대기 수").isEqualTo(1.0);
        assertThat(waiters.size()).as("대기가 끝난 뒤 등록부").isZero();
    }

    @Test
    @DisplayName("fallback 뒤 컨슈머가 같은 항목을 처리해도 둘째 행이 없고 항목은 ack 되며 재고 예약은 그대로다")
    void pollCoupon_fallback이_넣은_행을_만나면_행을_늘리지_않고_ack한다() {
        strategy.issue(couponId, requester());
        String requestId = streamedRequestId();

        int handled = consumer.pollCoupon(couponId);

        assertThat(handled).isEqualTo(1);
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(1L);
        assertThat(fixture.pendingCount(couponId)).isZero();
        assertThat(stockRepository.streamLength(couponId)).isZero();
        assertThat(fixture.status(requestId)).isEqualTo("SUCCESS");
        // 되돌리면 DB 에는 행이 있는데 재고만 늘어난다
        assertThat(stockRepository.getStock(couponId)).isEqualTo(9L);
    }

    @Test
    @DisplayName("대기 중 Redis 읽기가 실패하면 상한을 기다리지 않고 바로 fallback 으로 행을 넣어 SUCCESS 로 답한다")
    void issue_대기_중_Redis_오류면_바로_fallback이다() {
        doThrow(new QueryTimeoutException("시험용 Redis 읽기 장애")).when(asyncService).getStatus(anyString());
        double before = fallbacks();
        long startedAt = System.nanoTime();

        CouponIssueResult result = strategy.issue(couponId, requester());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        assertThat(elapsedMs).as("대기 상한(800ms) 전에 끝난 시간").isLessThan(700);
        assertThat(fallbacks() - before).isEqualTo(1.0);
        assertThat(waiters.size()).as("Redis 오류로 끝난 뒤 등록부").isZero();
    }

    @Test
    @DisplayName("fallback 의 DB 저장이 실패하면 UNAVAILABLE(503) 로 답하고 행은 없다")
    void issue_fallback_DB_실패면_UNAVAILABLE이다() {
        doThrow(new DataAccessResourceFailureException("시험용 DB 장애")).when(writer).insertAll(anyLong(), anyMap());

        CouponIssueResult result = strategy.issue(couponId, requester());

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.UNAVAILABLE);
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isZero();
        assertThat(waiters.size()).as("DB 실패로 끝난 뒤 등록부").isZero();
    }
}
