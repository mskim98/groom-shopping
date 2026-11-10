package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import groom.backend.application.coupon.CouponIssueBatchWriter;
import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        reset(writer);
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
}
