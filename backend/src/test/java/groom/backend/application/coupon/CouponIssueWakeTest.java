package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 쿠폰 발급이 자기 요청의 완료 신호를 기다려 동기로 답하는지 본다 (실 PostgreSQL + Redis 필요)
 *
 * <p>재확인 간격을 5초로 둬서, 신호로 깼다면 간격 안에 끝난다는 사실이 키 재확인이 아니라 메시지 덕이 되게 한다.
 * 컨슈머는 시험 설정이 자동으로 켜지 않으므로 시험이 직접 켠다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "coupon.issue.wait-timeout-ms=10000",
        "coupon.issue.wait-recheck-ms=5000"
})
@DisplayName("쿠폰 발급 대기 - 완료 신호로 깨어 동기로 답함")
class CouponIssueWakeTest {

    @Autowired
    private StreamIssueStrategy strategy;
    @Autowired
    private CouponIssueWaitService waitService;
    @Autowired
    private CouponStreamConsumer consumer;
    @Autowired
    private CouponStockRedisRepository stockRepository;
    @Autowired
    private CouponIssueBatchWriter writer;
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
    @MockitoSpyBean
    private CouponIssueWaiters waiters; // 대기자 등록 직전에 컨슈머 대역을 끼워 넣는다
    @MockitoSpyBean
    private CouponIssueSignal signal; // 신호를 낸 시각을 잰다

    private CouponStreamFixture fixture;
    private Long couponId;
    private Long userId;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        couponId = fixture.coupon(10);
        stockRepository.initStock(couponId, 10L);
        userId = fixture.user();
        CouponStreamFixture.awaitSubscription(redis, waiters);
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
        reset(waiters, signal);
        fixture.cleanup();
    }

    private User requester() {
        return new User(userId, "wake-" + userId + "@test.com", "pw", "대기시험", Role.ROLE_USER, Grade.BRONZE, null, null);
    }

    private double wakes(String source) {
        return meters.counter("coupon.issue.wake", "source", source).count();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("컨슈머가 대기자 등록 300ms 뒤에 커밋하면 재확인 간격(5초)이 아니라 신호로 깨어 1.5초 안에 SUCCESS 와 발급 번호를 돌려주고 pubsub 으로 센다")
    void issue_신호로_깨면_1500ms안에_SUCCESS와_발급번호고_pubsub으로_센다() {
        double before = wakes("pubsub");
        long[] signaledAt = new long[1];
        doAnswer(invocation -> {
            signaledAt[0] = System.nanoTime();
            return invocation.callRealMethod();
        }).when(signal).publish(anyMap());
        // 등록 직후의 키 읽기는 WAITING 이다. 컨슈머를 300ms 뒤에 켜서 신호로만 깰 수 있게 한다
        doAnswer(invocation -> {
            Object registered = invocation.callRealMethod();
            CompletableFuture.runAsync(() -> {
                sleep(300);
                consumer.start();
            });
            return registered;
        }).when(waiters).register(anyString());

        CouponIssueResult result = strategy.issue(couponId, requester());
        long returnedAt = System.nanoTime();

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        assertThat(result.couponIssueId())
                .isEqualTo(couponIssueRepository.findByCouponIdAndUserId(couponId, userId).orElseThrow().getId());
        assertThat((returnedAt - signaledAt[0]) / 1_000_000).as("신호 호출 뒤 반환까지(ms)").isLessThan(1500);
        assertThat(wakes("pubsub") - before).as("pubsub 으로 끝난 대기 수").isEqualTo(1.0);
        assertThat(waiters.size()).as("대기가 끝난 뒤 등록부").isZero();
    }

    @Test
    @DisplayName("컨슈머가 FAILED 로 마감한 요청은 신호로 깨어 실패 결과(이미 발급)를 돌려주고 등록부가 빈다")
    void issue_FAILED_신호면_실패결과를_돌려주고_등록부가_빈다() {
        // 접수 경로를 거치지 않은 다른 요청의 행이 이미 있다 - 컨슈머가 진짜 중복으로 FAILED 를 남긴다
        writer.insertAll(couponId, Map.of(userId, fixture.requestId()));
        consumer.start();

        CouponIssueResult result = strategy.issue(couponId, requester());

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.ALREADY_ISSUED);
        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        assertThat(waiters.size()).as("실패로 끝난 뒤 등록부").isZero();
    }

    @Test
    @DisplayName("신호가 대기자 등록보다 먼저 나가 놓쳐도 등록 직후 키를 한 번 읽어 1초 안에 SUCCESS 를 돌려주고 key 로 센다")
    void issue_신호가_등록보다_먼저면_키읽기로_1000ms안에_SUCCESS고_key로_센다() {
        double before = wakes("key");
        // 컨슈머가 대기자 등록보다 먼저 끝낸 경우 - 등록 직전에 같은 스레드에서 확정과 신호를 낸다
        doAnswer(invocation -> {
            consumer.pollCoupon(couponId);
            return invocation.callRealMethod();
        }).when(waiters).register(anyString());
        long startedAt = System.nanoTime();

        CouponIssueResult result = strategy.issue(couponId, requester());
        long returnedAt = System.nanoTime();

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        assertThat((returnedAt - startedAt) / 1_000_000).as("요청부터 반환까지(ms) - 재확인이면 5000").isLessThan(1000);
        assertThat(wakes("key") - before).as("key 로 끝난 대기 수").isEqualTo(1.0);
        assertThat(waiters.size()).as("대기가 끝난 뒤 등록부").isZero();
    }

    @Test
    @DisplayName("아무도 확정하지 않아 대기 상한에 닿아도 등록부가 비고 fallback 으로 센다")
    void issue_대기_상한에_닿아도_등록부가_비고_fallback으로_센다() {
        ReflectionTestUtils.setField(waitService, "waitTimeoutMs", 800L);
        ReflectionTestUtils.setField(waitService, "recheckMs", 300L);
        try {
            double before = wakes("fallback");

            CouponIssueResult result = strategy.issue(couponId, requester());

            assertThat(result.status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
            assertThat(wakes("fallback") - before).as("fallback 으로 끝난 대기 수").isEqualTo(1.0);
            assertThat(waiters.size()).as("대기 상한 뒤 등록부").isZero();
        } finally {
            ReflectionTestUtils.setField(waitService, "waitTimeoutMs", 10000L);
            ReflectionTestUtils.setField(waitService, "recheckMs", 5000L);
        }
    }
}
