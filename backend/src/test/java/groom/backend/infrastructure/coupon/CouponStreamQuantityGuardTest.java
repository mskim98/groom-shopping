package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.application.coupon.strategy.DbLockIssueStrategy;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * DB 경로와 스트림이 같은 쿠폰을 함께 발급해도 합계는 quantity 를 넘지 않는다
 *
 * <p>Redis 가 끊긴 동안 DB 경로가 발급하고, Redis 가 돌아온 뒤 스트림에 남은 항목이 확정된다.
 * 둘이 같은 쿠폰 행 잠금 아래서 {@code quantity − 발급 수} 를 읽으므로 합계가 어긋나지 않는다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 스트림 수량 상한")
class CouponStreamQuantityGuardTest {

    @Autowired
    private CouponStreamConsumer consumer;
    @Autowired
    private DbLockIssueStrategy dbLock;
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
    @DisplayName("수량 5 에서 DB 경로가 3장 발급한 뒤 스트림 4건이 오면 2건만 들어가고 2건은 품절로 실패한다")
    void pollCoupon_db경로가_먼저_발급하면_남은_자리까지만_넣는다() {
        Long couponId = fixture.coupon(5);
        stockRepository.initStock(couponId, 5L);
        List<Long> users = fixture.users(7);
        // Redis 장애 중에 DB 경로가 3장을 냈다 (Redis 재고와 명단은 그대로다)
        for (Long user : users.subList(0, 3)) {
            assertThat(dbLock.issueFor(couponId, user).status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        }
        // Redis 복구 뒤 접수된 4건이 스트림에 대기한다
        List<String> requestIds = new ArrayList<>();
        for (Long user : users.subList(3, 7)) {
            String requestId = fixture.requestId();
            stockRepository.enqueue(couponId, user, requestId);
            requestIds.add(requestId);
        }

        consumer.pollCoupon(couponId);

        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(5L);
        assertThat(requestIds.stream().filter(id -> "SUCCESS".equals(fixture.status(id))).count()).isEqualTo(2L);
        assertThat(requestIds.stream().filter(id -> "FAILED:COUPON_OUT_OF_STOCK".equals(fixture.status(id))).count())
                .isEqualTo(2L);
        // 앞선 항목부터 채운다. 순서가 바뀌면 먼저 온 사람이 떨어진다
        assertThat(fixture.status(requestIds.get(0))).isEqualTo("SUCCESS");
        assertThat(fixture.status(requestIds.get(1))).isEqualTo("SUCCESS");
        // 넘친 2건의 예약은 되돌린다: 재고 1 + 2, 명단에서도 빠진다
        assertThat(stockRepository.getStock(couponId)).isEqualTo(3L);
        assertThat(stockRepository.getIssuedUsers(couponId)).hasSize(2);
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(5L);
        assertThat(fixture.pendingCount(couponId)).isZero();
    }

    @Test
    @DisplayName("DB 경로 스레드와 스트림 확정이 동시에 달려도 발급 수는 quantity 와 같다")
    void pollCoupon_db경로와_동시에_달려도_합계는_수량이다() throws Exception {
        Long couponId = fixture.coupon(5);
        // Redis 재고가 실제보다 많다(복구 직후의 어긋남) - 확정 단계의 상한이 마지막 방어선이다
        stockRepository.initStock(couponId, 20L);
        List<Long> users = fixture.users(16);
        for (Long user : users.subList(0, 8)) {
            stockRepository.enqueue(couponId, user, fixture.requestId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            go.await();
            consumer.pollCoupon(couponId);
            return null;
        }));
        for (Long user : users.subList(8, 16)) {
            futures.add(pool.submit(() -> {
                go.await();
                dbLock.issueFor(couponId, user);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(5L);
    }
}
