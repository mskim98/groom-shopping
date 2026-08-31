package groom.backend.application.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 전 발급 전략이 지켜야 하는 불변식을 같은 단언으로 검증한다 (실 PostgreSQL + Redis 필요)
 *
 * <p>라운드마다 구현이 달라도 <b>지켜야 할 것은 같다</b>는 점이 이 테스트의 존재 이유다
 * 전략이 늘어나면 파라미터만 늘고 단언은 그대로다 - 그래야 새 라운드가 정확성을 깎으며
 * 성능을 산 것인지 아닌지가 드러난다
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 동시 요청을 검증하는 테스트라
 * 각 스레드가 자기 트랜잭션으로 커밋해야 하고, 테스트 트랜잭션 안에 가두면 아무것도 보이지 않는다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("발급 전략 계약 테스트")
class CouponIssueStrategyContractTest {

    private static final long STOCK = 10L;
    private static final int CONCURRENT_USERS = 50;
    private static final int SAME_USER_ATTEMPTS = 20;
    private static final String FIXTURE_EMAIL_PREFIX = "contract_";

    @Autowired
    private List<CouponIssueStrategy> strategies;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private Long couponId;
    private final List<User> users = new ArrayList<>();

    // 파라미터 공급자가 인스턴스 메서드라 주입받은 빈 목록을 그대로 쓸 수 있다
    // PER_CLASS 수명주기를 쓴 이유가 이것이다
    Stream<Named<CouponIssueStrategy>> allStrategies() {
        // 이름을 붙이지 않으면 결과에 객체 해시가 찍혀 어느 라운드가 깨졌는지 알 수 없다
        return strategies.stream().map(s -> Named.of(s.name(), s));
    }

    @BeforeEach
    void setUp() {
        cleanUp();

        users.clear();
        for (int i = 1; i <= CONCURRENT_USERS; i++) {
            UserJpaEntity saved = userRepository.save(UserJpaEntity.builder()
                    .email(FIXTURE_EMAIL_PREFIX + i + "@test.com")
                    .password("pw")
                    .name("계약테스터" + i)
                    .role(Role.ROLE_USER)
                    .grade(Grade.BRONZE)
                    .build());
            users.add(new User(saved.getId(), saved.getEmail(), "pw", saved.getName(),
                    Role.ROLE_USER, Grade.BRONZE, null, null));
        }

        Coupon coupon = couponRepository.save(CouponCreateRequest.builder()
                .name("계약 검증 쿠폰").description("전략 계약 테스트용").quantity(STOCK).amount(1000)
                .type(CouponType.DISCOUNT).expireDate(LocalDate.now().plusDays(30)).build()
                .toEntity());
        couponId = coupon.getId();

        // 게이트를 쓰는 전략이 재고를 찾을 수 있어야 한다. DB 수량과 같은 값을 심는다
        redisTemplate.opsForValue()
                .set(CouponStockRedisRepository.stockKey(couponId), String.valueOf(STOCK));
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        // deleteAll() 을 쓰지 않는다. 이 테스트가 만들지 않은 쿠폰까지 지우면
        // 같은 DB 를 쓰는 측정용 쿠폰이 사라져 부하 실행이 전건 404 로 끝난다 (실제로 한 번 겪었다)
        if (couponId != null) {
            couponIssueRepository.deleteAll(couponIssueRepository.findByCoupon_Id(couponId));
            // 발급자 SET 을 남기면 다음 케이스가 전건 중복으로 깨진다
            redisTemplate.delete(List.of(
                    CouponStockRedisRepository.stockKey(couponId),
                    CouponStockRedisRepository.issuedUsersKey(couponId)));
            couponRepository.findById(couponId).ifPresent(couponRepository::delete);
            couponId = null;
        }
        // 이 클래스가 만든 사용자만 지운다. deleteAll() 은 시드 사용자까지 지워 남의 테스트를 깬다
        for (int i = 1; i <= CONCURRENT_USERS; i++) {
            userRepository.findByEmail(FIXTURE_EMAIL_PREFIX + i + "@test.com")
                    .ifPresent(userRepository::delete);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("재고보다 많은 동시 요청에도 발급 수가 재고를 넘지 않는다")
    void 재고보다_많은_동시요청에도_발급수가_재고를_넘지_않는다(CouponIssueStrategy strategy) throws Exception {
        boolean async = 동시에_실행(users, u -> strategy.issue(couponId, u));
        확정_대기(async);

        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(STOCK);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("같은 사용자의 동시 중복 요청은 1장만 발급된다")
    void 같은_사용자의_동시_중복요청은_1장만_발급된다(CouponIssueStrategy strategy) throws Exception {
        User one = users.get(0);

        boolean async = 동시에_실행(Collections.nCopies(SAME_USER_ATTEMPTS, one),
                u -> strategy.issue(couponId, u));
        확정_대기(async);

        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, one.getId())).isEqualTo(1);
    }

    /**
     * 모두 같은 순간에 출발시킨다. 순차로 부르면 경합이 만들어지지 않아 어떤 구현이든 통과한다
     *
     * @return 접수만 하고 끝난 결과가 하나라도 있으면 true (확정 대기가 필요하다는 뜻)
     */
    private boolean 동시에_실행(List<User> targets, Function<User, CouponIssueResult> action)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(targets.size(), 32));
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(targets.size());
        AtomicBoolean sawAccepted = new AtomicBoolean(false);

        for (User target : targets) {
            pool.submit(() -> {
                try {
                    ready.await();
                    CouponIssueResult result = action.apply(target);
                    if (result != null && result.status() == CouponIssueResult.Status.ACCEPTED) {
                        sawAccepted.set(true);
                    }
                } catch (Exception e) {
                    // 예외로 끝난 요청도 발급되지 않은 것이므로 단언에는 그대로 반영된다
                } finally {
                    done.countDown();
                }
            });
        }
        ready.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        return sawAccepted.get();
    }

    /**
     * 비동기 확정 전략은 응답 시점에 아직 저장되지 않았다
     * 발급 수가 더 이상 늘지 않을 때까지 기다린 뒤에 세야 "덜 처리된 상태"를 결함으로 오인하지 않는다
     */
    private void 확정_대기(boolean async) throws InterruptedException {
        if (!async) {
            return;
        }
        long previous = -1;
        for (int i = 0; i < 30; i++) {
            long current = couponIssueRepository.countByCoupon_Id(couponId);
            if (current == previous && current > 0) {
                return;
            }
            previous = current;
            Thread.sleep(500);
        }
    }
}
