package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import groom.backend.application.coupon.CouponIssueBatchWriter;
import groom.backend.application.coupon.CouponIssueSignal;
import groom.backend.application.coupon.CouponIssueWaiters;
import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoConsumer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 스트림 컨슈머의 한 번 읽기 · 확정 (실 PostgreSQL + Redis 필요)
 *
 * <p>XREADGROUP → 쿠폰 행 잠금 아래 한 문장 INSERT → 커밋 → 그 뒤에 XACK · 상태 · 캐시 비움 순서다.
 * 확정이 커밋되기 전에 ack 가 나가면 그 사이 죽은 요청이 영영 재전달되지 않는다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 스트림 컨슈머")
class CouponStreamConsumerTest {

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
    @Qualifier("couponCacheManager")
    private CacheManager cacheManager;
    @MockitoSpyBean
    private CouponIssueBatchWriter writer;
    @MockitoSpyBean
    private CouponIssueSignal signal;
    @Autowired
    private CouponIssueWaiters waiters;

    private CouponStreamFixture fixture;
    private Long couponId;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        couponId = fixture.coupon(10);
        stockRepository.initStock(couponId, 10L);
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
        reset(writer, signal);
        fixture.cleanup();
    }

    private List<String> enqueue(List<Long> userIds) {
        List<String> requestIds = new ArrayList<>();
        for (Long userId : userIds) {
            String requestId = fixture.requestId();
            stockRepository.enqueue(couponId, userId, requestId);
            requestIds.add(requestId);
        }
        return requestIds;
    }

    @Test
    @DisplayName("3건을 읽으면 발급 행 3개 · XPENDING 0 · 상태 SUCCESS 3개이고 내 쿠폰 캐시가 비워진다")
    void pollCoupon_세건이면_행_ack_상태가_모두_맞다() {
        List<Long> users = fixture.users(3);
        List<String> requestIds = enqueue(users);
        Cache cache = cacheManager.getCache("user-coupons-list-cache");
        cache.put(users.get(0), new ArrayList<>());

        int handled = consumer.pollCoupon(couponId);

        assertThat(handled).isEqualTo(3);
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(3L);
        assertThat(fixture.pendingCount(couponId)).isZero();
        assertThat(requestIds).allSatisfy(id -> assertThat(fixture.status(id)).isEqualTo("SUCCESS"));
        // searchMyCoupon 은 userId 키로 캐시된다. 비우지 않으면 방금 받은 쿠폰이 목록에 5분간 안 보인다
        assertThat(cache.get(users.get(0))).isNull();
        // 확정된 항목은 스트림에서도 지워 스트림이 요청 수만큼 자라지 않는다
        assertThat(stockRepository.streamLength(couponId)).isZero();
    }

    @Test
    @DisplayName("INSERT 가 예외를 던지면 ack 도 상태 변경도 없고 항목은 PEL 에 남는다")
    void pollCoupon_insert가_실패하면_ack하지_않는다() {
        List<String> requestIds = enqueue(fixture.users(3));
        doThrow(new IllegalStateException("insert 실패")).when(writer).insertAll(anyLong(), anyMap());

        assertThatThrownBy(() -> consumer.pollCoupon(couponId)).isInstanceOf(IllegalStateException.class);

        assertThat(fixture.pendingCount(couponId)).isEqualTo(3L);
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isZero();
        assertThat(requestIds).allSatisfy(id -> assertThat(fixture.status(id)).isEqualTo("WAITING"));
        // 재고 예약도 그대로다 - 확정되지 않았다고 되돌리면 재전달이 오면 +1 씩 부푼다
        assertThat(stockRepository.getStock(couponId)).isEqualTo(7L);
    }

    @Test
    @DisplayName("다른 요청이 이미 그 사용자의 행을 가졌다면 진짜 중복이라 실패로 남기고 재고만 되돌린다")
    void pollCoupon_다른_요청의_행이_있으면_중복으로_실패한다() {
        Long user = fixture.user();
        // 접수 경로를 거치지 않은 행(구 요청 · DB 경로)이 이미 있다
        writer.insertAll(couponId, java.util.Map.of(user, fixture.requestId()));
        String requestId = enqueue(List.of(user)).get(0);

        consumer.pollCoupon(couponId);

        assertThat(fixture.status(requestId)).isEqualTo("FAILED:COUPON_ALREADY_ISSUED");
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(1L);
        // 재고만 되돌린다. 명단에서 빼면 게이트가 그를 다시 통과시켜 같은 실패를 반복한다
        assertThat(stockRepository.getStock(couponId)).isEqualTo(10L);
        assertThat(stockRepository.getIssuedUsers(couponId)).contains(String.valueOf(user));
        assertThat(fixture.pendingCount(couponId)).isZero();
    }

    @Test
    @DisplayName("종료된 쿠폰의 항목은 확정하지 않고 자리를 되돌리며 COUPON_NOT_FOUND 로 실패한다")
    void pollCoupon_종료된_쿠폰이면_되돌리고_실패한다() {
        Long inactive = fixture.coupon(10, false);
        stockRepository.initStock(inactive, 10L);
        Long user = fixture.user();
        String requestId = fixture.requestId();
        stockRepository.enqueue(inactive, user, requestId);

        consumer.pollCoupon(inactive);

        assertThat(fixture.status(requestId)).isEqualTo("FAILED:COUPON_NOT_FOUND");
        assertThat(couponIssueRepository.countByCoupon_Id(inactive)).isZero();
        assertThat(stockRepository.getStock(inactive)).isEqualTo(10L);
        assertThat(stockRepository.getIssuedUsers(inactive)).isEmpty();
        assertThat(fixture.pendingCount(inactive)).isZero();
    }

    @Test
    @DisplayName("루프를 시작하면 접수한 항목이 몇 초 안에 확정된다")
    void start_루프가_접수한_항목을_확정한다() {
        List<String> requestIds = enqueue(fixture.users(2));

        consumer.start();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(requestIds).allSatisfy(id -> assertThat(fixture.status(id)).isEqualTo("SUCCESS")));
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(2L);
        consumer.stop();
        assertThat(consumer.isRunning()).isFalse();
    }

    @Test
    @DisplayName("커밋이 끝난 뒤 ack 보다 먼저 요청마다 SUCCESS 신호를 낸다")
    void pollCoupon_커밋_뒤_ack_전에_요청마다_SUCCESS_신호를_낸다() throws Exception {
        CouponStreamFixture.awaitSubscription(redis, waiters);
        List<String> requestIds = enqueue(fixture.users(3));
        Map<String, CompletableFuture<String>> waiting = new HashMap<>();
        requestIds.forEach(id -> waiting.put(id, waiters.register(id)));
        long[] rowsAtSignal = {-1};
        long[] pendingAtSignal = {-1};
        doAnswer(invocation -> {
            rowsAtSignal[0] = couponIssueRepository.countByCoupon_Id(couponId);
            pendingAtSignal[0] = fixture.pendingCount(couponId);
            return invocation.callRealMethod();
        }).when(signal).publish(anyMap());
        try {
            consumer.pollCoupon(couponId);

            for (CompletableFuture<String> waiter : waiting.values()) {
                assertThat(waiter.get(3, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
            }
            assertThat(rowsAtSignal[0]).as("신호를 낸 시점의 커밋된 행 수").isEqualTo(3L);
            assertThat(pendingAtSignal[0]).as("신호를 낸 시점의 미확인 항목 수(ack 전)").isEqualTo(3L);
        } finally {
            waiting.forEach(waiters::remove);
        }
    }

    @Test
    @DisplayName("컨슈머 4개가 서로 다른 이름으로 떠서 2000건을 한 번씩만 넣고 모두 ack 하며 stop 이 4개를 모두 멈춘다")
    void start_컨슈머_4개가_2000건을_한번씩_넣고_stop이_모두_멈춘다() {
        Long big = fixture.coupon(2000);
        stockRepository.initStock(big, 2000L);
        for (Long userId : fixture.users(2000)) {
            stockRepository.enqueue(big, userId, fixture.requestId());
        }

        consumer.start();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(couponIssueRepository.countByCoupon_Id(big)).isEqualTo(2000L);
            assertThat(fixture.pendingCount(big)).isZero();
            assertThat(stockRepository.streamLength(big)).isZero();
            assertThat(consumerNames(big)).hasSize(4);
            assertThat(loopThreads()).hasSize(4);
        });
        assertThat(consumerNames(big)).allSatisfy(name -> assertThat(name).startsWith(consumer.consumerName() + "-"));

        consumer.stop();

        assertThat(loopThreads()).isEmpty();
        assertThat(consumer.isRunning()).isFalse();
    }

    private Set<String> consumerNames(Long coupon) {
        return redis.opsForStream().consumers(CouponStockRedisRepository.streamKey(coupon), CouponStreamConsumer.GROUP)
                .stream().map(XInfoConsumer::consumerName).collect(Collectors.toSet());
    }

    private List<Thread> loopThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("coupon-stream-consumer")).toList();
    }
}
