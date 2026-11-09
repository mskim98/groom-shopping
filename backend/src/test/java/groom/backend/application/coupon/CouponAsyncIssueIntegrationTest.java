package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 7.3 / 7.3a 쿠폰 비동기 발급 통합 테스트 (실 PostgreSQL + Redis + Kafka 필요).
 *
 * <p>enqueue → 대기열 등록·WAITING, process → 발급·SUCCESS 흐름을 검증한다.
 * 컨슈머의 비동기 타이밍에 의존하지 않도록 process() 를 직접 호출해 처리 로직을 검증한다.
 *
 * <p>게이트는 {@code enqueue} 에 있고 확정 경로는 재고를 다시 검사하지 않는다.
 * 어느 경로를 탔는지는 <b>Redis 재고값</b>으로 판별한다 - DB 수량은 이제 아무도 고치지 않아
 * 경로 판별의 근거가 되지 못한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 비동기 발급 통합 테스트")
class CouponAsyncIssueIntegrationTest {

    // DB 수량. 재고를 심는 케이스는 이 값과 다른 수를 심어 Lua/폴백을 구분한다
    private static final long DB_QUANTITY = 100L;
    // Lua 경로 검증용 Redis 재고. DB 수량과 다르게 둬야 폴백의 initStock 덮어쓰기와 구별된다
    private static final long REDIS_STOCK = 5L;

    @Autowired
    private CouponAsyncIssueService couponAsyncIssueService;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private CouponStockRedisRepository couponStockRedisRepository;

    private Long userId;
    private Long couponId;
    private String lastRequestId;
    // 케이스마다 requestId 가 여러 개 생기므로 전부 모아 뒀다가 정리한다
    private final List<String> requestIds = new ArrayList<>();

    // 이 클래스가 만드는 사용자만 지운다. userRepository.deleteAll() 을 쓰지 않는다
    private static final List<String> FIXTURE_EMAILS = List.of("async@test.com");

    // deleteAll() 은 V2 시드 사용자(admin@test.com · user_N@test.com)까지 지운다.
    // payment 는 users 로 ON DELETE CASCADE 인데 payment_compensation 에는 CASCADE 가 없어,
    // 부하 실행이 남긴 보상 레코드가 있으면 fk_payment_compensation_payment 위반으로 삭제가 막힌다.
    // 테스트가 자기 것만 정리하면 남의 데이터 상태가 결과를 바꾸지 않는다
    // 이 클래스가 만든 쿠폰만 이름으로 찾아 지운다
    private void deleteFixtureCoupons() {
        if (couponId != null) {
            couponIssueRepository.deleteAll(couponIssueRepository.findByCoupon_Id(couponId));
            couponRepository.findById(couponId).ifPresent(couponRepository::delete);
            couponId = null;
        }
    }

    // 폴백 실측이 만든 사용자. 발급 행이 이들을 참조하므로 쿠폰 정리 뒤에 지운다
    private final List<Long> extraUserIds = new ArrayList<>();

    private void deleteFixtureUsers() {
        FIXTURE_EMAILS.forEach(email ->
                userRepository.findByEmail(email).ifPresent(userRepository::delete));
        userRepository.deleteAllById(extraUserIds);
        extraUserIds.clear();
    }

    @BeforeEach
    void setUp() {
        // deleteAll() 을 쓰지 않는다. 이 테스트가 만들지 않은 쿠폰까지 지우면
        // 같은 DB 를 쓰는 측정용 쿠폰이 사라져 부하 실행이 전건 404 로 끝난다 (실제로 한 번 겪었다)
        deleteFixtureCoupons();
        deleteFixtureUsers();
        requestIds.clear();

        UserJpaEntity user = userRepository.save(UserJpaEntity.builder()
                .email("async@test.com").password("pw").name("비동기테스터")
                .role(Role.ROLE_USER).grade(Grade.BRONZE).build());
        userId = user.getId();

        Coupon coupon = couponRepository.save(CouponCreateRequest.builder()
                .name("비동기 쿠폰").description("비동기 발급용").quantity(DB_QUANTITY).amount(1000)
                .type(CouponType.DISCOUNT).expireDate(LocalDate.now().plusDays(30)).build()
                .toEntity());
        couponId = coupon.getId();

        // 앞선 실행의 잔여 키가 남아 있으면 경로 판별이 흐려지므로 시작 시점에도 비운다
        deleteCouponKeys();
    }

    @AfterEach
    void tearDown() {
        // Redis 잔여 키 정리 (재고/발급자 SET/상태)
        // 발급자 SET 을 남기면 다음 테스트가 COUPON_ALREADY_ISSUED 로 깨진다
        deleteCouponKeys();
        deleteFixtureCoupons();
        deleteFixtureUsers();
        List<String> statusKeys = new ArrayList<>();
        for (String requestId : requestIds) {
            statusKeys.add("coupon:issue:status:" + requestId);
        }
        if (lastRequestId != null) {
            statusKeys.add("coupon:issue:status:" + lastRequestId);
        }
        if (!statusKeys.isEmpty()) {
            redisTemplate.delete(statusKeys);
        }
    }

    // 재고 키가 없으면 판정할 근거가 없다. 접수는 여기서 기다리지 않고 즉시 돌려보낸다
    // 통과시키면 그 뒤에 판정할 사람이 아무도 없어 무제한 발급이 되고,
    // 심어질 때까지 기다리면 그 대기가 그대로 요청 스레드 점유가 된다
    @Test
    @DisplayName("재고 미워밍업 시 접수는 브로커에 싣지 않고 보류로 응답한다")
    void enqueue_미워밍업이면_싣지_않는다() {
        int statusKeysBefore = countStatusKeys();

        // when
        var result = couponAsyncIssueService.enqueue(couponId, userId);

        // then - 상태 키가 늘지 않았다 = 발행도 없었다
        assertThat(result.status())
                .isEqualTo(groom.backend.application.coupon.strategy.CouponIssueResult.Status.UNAVAILABLE);
        assertThat(countStatusKeys()).isEqualTo(statusKeysBefore);
        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isEmpty();
    }

    // 접수가 만들지 않는 이벤트(구 메시지 · 복구 중 발행분)를 컨슈머가 받았을 때다
    // 재고 키가 없으면 초기화 잠금을 잡고 심은 뒤 다시 판정한다
    // 심는 값이 맞아떨어지는 이유 : COUNT 는 확정된 발급만 세므로 처리 중인 이벤트가 그 몫을 소비한다
    @Test
    @DisplayName("게이트 예약이 없는 이벤트는 컨슈머가 재고를 심고 다시 판정한다")
    void process_예약없는_이벤트는_재고를_심고_재판정한다() {
        String requestId = newRequestId();

        // when - 재고 키가 없는 상태에서 gateReserved=false 이벤트가 도착한다
        couponAsyncIssueService.process(new CouponIssueRequestEvent(requestId, couponId, userId, false));

        // then - 재고를 심고(DB 수량 − 확정 발급 수) 재판정해 확정까지 갔다
        assertThat(couponAsyncIssueService.getStatus(requestId)).isEqualTo("SUCCESS");
        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isNotEmpty();
        assertThat(couponStockRedisRepository.getStock(couponId)).isEqualTo(DB_QUANTITY - 1);
        // 확정 경로는 coupon 테이블을 만지지 않는다. 감산이 되살아나면 이 단언이 깨진다
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(DB_QUANTITY);
    }

    // 폴백 실측 - 키가 userId 라 재고 키 부재를 컨슈머 4 개가 같은 시점에 발견한다
    // 초과 발급은 SET NX 가 막고, 초기화 잠금은 DB 집계를 컨슈머 수만큼에서 1 회로 줄인다
    // 집계 횟수는 재초기화 로그로 센다 - 그 로그는 COUNT 직후 조건 없이 한 번 찍힌다
    // 대조군 : ensureStockInitialized 의 tryLock 을 걷어내고 돌리면 reinit 이 컨슈머 수만큼 나와야 한다
    @Test
    @DisplayName("재고 키 부재에서 컨슈머 4개가 동시에 재판정해도 DB 집계는 1회, 발급 수는 발행 수량과 같다")
    void process_컨슈머4개가_동시에_키부재를_만나도_집계는_한번이다() throws Exception {
        int consumers = 4;
        // 합이 발행 수량의 2 배 - 품절까지 가야 초과 발급 여부가 드러난다
        int perConsumer = (int) (DB_QUANTITY * 2 / consumers);
        String runTag = UUID.randomUUID().toString().substring(0, 8);
        List<Long> userIds = userRepository.saveAll(IntStream.range(0, consumers * perConsumer)
                        .mapToObj(i -> UserJpaEntity.builder()
                                .email("fallback-" + runTag + "-" + i + "@test.com").password("pw").name("폴백측정")
                                .role(Role.ROLE_USER).grade(Grade.BRONZE).build())
                        .toList())
                .stream().map(UserJpaEntity::getId).toList();
        extraUserIds.addAll(userIds);

        List<List<CouponIssueRequestEvent>> batches = new ArrayList<>();
        for (int c = 0; c < consumers; c++) {
            batches.add(userIds.subList(c * perConsumer, (c + 1) * perConsumer).stream()
                    .map(uid -> new CouponIssueRequestEvent(newRequestId(), couponId, uid, false))
                    .toList());
        }

        Logger logger = (Logger) LoggerFactory.getLogger(CouponAsyncIssueService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when - 네 스레드가 같은 순간에 각자의 배치를 처리한다
        ExecutorService pool = Executors.newFixedThreadPool(consumers);
        CountDownLatch start = new CountDownLatch(1);
        long elapsedMs;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (List<CouponIssueRequestEvent> batch : batches) {
                futures.add(pool.submit(() -> {
                    start.await();
                    couponAsyncIssueService.processBatch(batch);
                    return null;
                }));
            }
            long t0 = System.nanoTime();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        } finally {
            pool.shutdownNow();
            logger.detachAppender(appender);
        }

        long reinit = appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("[COUPON_STOCK_REINITIALIZED]")).count();
        long issued = couponIssueRepository.findByCoupon_Id(couponId).size();
        Map<String, Long> statuses = batches.stream().flatMap(List::stream)
                .collect(Collectors.groupingBy(e -> String.valueOf(couponAsyncIssueService.getStatus(e.requestId())),
                        TreeMap::new, Collectors.counting()));
        System.out.printf("[FALLBACK_MEASURE] consumers=%d events=%d quantity=%d reinit=%d issued=%d redisStock=%s elapsedMs=%d statuses=%s%n",
                consumers, consumers * perConsumer, DB_QUANTITY, reinit, issued,
                couponStockRedisRepository.getStock(couponId), elapsedMs, statuses);

        // then
        assertThat(reinit).isEqualTo(1);
        assertThat(issued).isEqualTo(DB_QUANTITY);
        assertThat(couponStockRedisRepository.getStock(couponId)).isZero();
    }

    // 증명: 접수 게이트가 재고를 깎고, 컨슈머는 그 예약을 DB 로 확정만 한다
    // Redis 재고가 5 → 4 로 줄어든 것이 근거다. 폴백이었다면 커밋 후 initStock(99) 로 덮여 4 가 될 수 없다
    @Test
    @DisplayName("게이트 예약분은 컨슈머가 재고를 다시 깎지 않고 확정만 한다")
    void process_게이트예약분은_재고를_다시_깎지_않는다() {
        // given - Lua 가 볼 재고를 DB 수량과 다른 값으로 심는다
        couponStockRedisRepository.initStock(couponId, REDIS_STOCK);
        // 접수 단계를 흉내 낸다. enqueue 를 쓰면 실제 컨슈머가 같은 이벤트를 한 번 더 처리한다
        couponStockRedisRepository.tryIssue(couponId, userId);
        String requestId = newRequestId();

        // when
        couponAsyncIssueService.process(new CouponIssueRequestEvent(requestId, couponId, userId, true));

        // then - 상태 SUCCESS + DB 발급. DB 수량은 그대로다
        assertThat(couponAsyncIssueService.getStatus(requestId)).isEqualTo("SUCCESS");
        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isPresent();
        assertThat(couponIssueRepository.findCouponIssueByUserId(userId)).hasSize(1);
        // quantity 는 불변 발행 수량이 됐다. 이 단언이 깨지면 확정 경로에 감산이 되살아난 것이고,
        // 그러면 같은 쿠폰의 모든 확정이 한 행을 두고 다시 줄을 선다
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(DB_QUANTITY);

        // then - Lua 경로만 만들 수 있는 흔적: 재고 DECR + 발급자 SADD
        assertThat(couponStockRedisRepository.getStock(couponId)).isEqualTo(REDIS_STOCK - 1);
        assertThat(redisTemplate.opsForSet()
                .isMember(CouponStockRedisRepository.issuedUsersKey(couponId), userId.toString()))
                .isTrue();
    }

    // 증명: 재고가 0 이면 DB 에 수량이 남아 있어도 게이트가 접수 단계에서 막는다
    // 이것이 Task 6 의 핵심이다 - 마감이 확정된 요청은 브로커와 컨슈머 자원을 아예 쓰지 않는다
    @Test
    @DisplayName("Redis 재고 소진 시 접수 게이트가 거부 - 브로커에 싣지 않는다")
    void enqueue_재고가_0이면_게이트가_품절로_거부한다() {
        // given - DB 수량은 100 인데 Redis 재고만 0
        couponStockRedisRepository.initStock(couponId, 0L);
        // WAITING 상태 키는 게이트를 통과한 뒤에만 쓰인다. 개수가 늘지 않으면 발행도 없었다는 뜻이다
        int statusKeysBefore = countStatusKeys();

        // when & then - 접수 단계에서 끊긴다
        assertThat(couponAsyncIssueService.enqueue(couponId, userId).status())
                .isEqualTo(groom.backend.application.coupon.strategy.CouponIssueResult.Status.SOLD_OUT);

        // then - 브로커에 싣지 않았고 DB 도 손대지 않았다
        assertThat(countStatusKeys()).isEqualTo(statusKeysBefore);
        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isEmpty();
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(DB_QUANTITY);
    }

    // 증명: 중복 발급 차단이 DB 조회가 아니라 Lua 의 발급자 SET(SISMEMBER)에서 이뤄진다
    // 두 번째 요청에서 재고가 더 줄지 않는 것이 Lua 가 차감 전에 막았다는 근거다
    @Test
    @DisplayName("같은 사용자의 두 번째 요청은 접수 게이트가 거부 - COUPON_ALREADY_ISSUED")
    void enqueue_같은_사용자가_두_번_요청하면_게이트가_중복으로_거부한다() {
        // given - 첫 발급은 접수 게이트 + 확정으로 끝낸다
        couponStockRedisRepository.initStock(couponId, REDIS_STOCK);
        couponStockRedisRepository.tryIssue(couponId, userId);
        String firstRequestId = newRequestId();
        couponAsyncIssueService.process(new CouponIssueRequestEvent(firstRequestId, couponId, userId, true));
        assertThat(couponAsyncIssueService.getStatus(firstRequestId)).isEqualTo("SUCCESS");

        // when & then - 두 번째는 접수에서 끊긴다
        assertThat(couponAsyncIssueService.enqueue(couponId, userId).status())
                .isEqualTo(groom.backend.application.coupon.strategy.CouponIssueResult.Status.ALREADY_ISSUED);

        // findByCouponIdAndUserId 는 Optional 이라 중복 행이 생기면 조회 자체가 깨진다. 행 수로 직접 센다
        assertThat(couponIssueRepository.findCouponIssueByUserId(userId)).hasSize(1);

        // then - 재고는 한 번만 차감된다 (중복 요청이 차감 전에 막혔다는 근거)
        assertThat(couponStockRedisRepository.getStock(couponId)).isEqualTo(REDIS_STOCK - 1);
        // DB 수량은 아무도 고치지 않는다. 재고 판정의 근거는 Redis 한 곳뿐이다
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(DB_QUANTITY);
    }

    // 증명: Lua 가 중복 검사를 재고 검사보다 먼저 한다
    // 재고를 1 로 두고 같은 사용자가 두 번 요청하면 두 번째 시점의 재고는 0 이다
    // 재고 검사가 앞서면 이 요청이 품절로 응답돼, 실패 집계에서 중복이 품절로 오분류된다
    // 재고 0 + 이미 발급 상태에서 ALREADY_ISSUED 가 나오는 것이 검사 순서의 유일한 근거다
    @Test
    @DisplayName("재고가 0 이어도 이미 발급받은 사용자는 품절이 아니라 중복으로 거부된다")
    void enqueue_재고가0이어도_이미_발급받았으면_중복으로_거부한다() {
        // given - 마지막 한 장을 이 사용자가 가져가 재고가 0 이 된 상태를 만든다
        couponStockRedisRepository.initStock(couponId, 1L);
        couponStockRedisRepository.tryIssue(couponId, userId);
        String firstRequestId = newRequestId();
        couponAsyncIssueService.process(new CouponIssueRequestEvent(firstRequestId, couponId, userId, true));
        assertThat(couponAsyncIssueService.getStatus(firstRequestId)).isEqualTo("SUCCESS");
        assertThat(couponStockRedisRepository.getStock(couponId)).isZero();

        // when & then - 같은 사용자가 품절 이후에 다시 요청하면 품절이 아니라 중복으로 분류된다
        assertThat(couponAsyncIssueService.enqueue(couponId, userId).status())
                .isEqualTo(groom.backend.application.coupon.strategy.CouponIssueResult.Status.ALREADY_ISSUED);

        // 재고는 0 에서 더 내려가지 않는다 (DECR 이전에 막혔다는 근거)
        assertThat(couponStockRedisRepository.getStock(couponId)).isZero();
        assertThat(couponIssueRepository.findCouponIssueByUserId(userId)).hasSize(1);
    }

    // 증명: 발급 이력이 없는 사용자에게는 품절 판정이 그대로 유지된다
    // 중복을 앞으로 당긴 변경이 품절 응답까지 삼키지 않았음을 고정한다
    @Test
    @DisplayName("발급 이력이 없는 사용자는 재고가 0 이면 그대로 품절로 거부된다")
    void enqueue_발급이력이_없으면_재고0은_그대로_품절이다() {
        // given - 발급자 SET 은 비어 있고 재고만 0
        couponStockRedisRepository.initStock(couponId, 0L);

        // when & then
        assertThat(couponAsyncIssueService.enqueue(couponId, userId).status())
                .isEqualTo(groom.backend.application.coupon.strategy.CouponIssueResult.Status.SOLD_OUT);

        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isEmpty();
    }

    // enqueue 를 거치면 실제 Kafka 컨슈머가 같은 이벤트를 한 번 더 처리해 발급이 중복될 수 있다
    // Lua 경로 케이스는 requestId 를 직접 만들어 process 만 호출한다
    private String newRequestId() {
        String requestId = UUID.randomUUID().toString();
        requestIds.add(requestId);
        return requestId;
    }

    // 접수 게이트가 요청을 잘랐는지 판정한다. WAITING 상태 키는 게이트 통과 후에만 쓰이므로
    // 개수가 그대로면 브로커 발행도 없었다는 뜻이다
    private int countStatusKeys() {
        Set<String> keys = redisTemplate.keys("coupon:issue:status:*");
        return keys == null ? 0 : keys.size();
    }

    private void deleteCouponKeys() {
        redisTemplate.delete(List.of(
                CouponStockRedisRepository.stockKey(couponId),
                CouponStockRedisRepository.issuedUsersKey(couponId)));
    }
}
