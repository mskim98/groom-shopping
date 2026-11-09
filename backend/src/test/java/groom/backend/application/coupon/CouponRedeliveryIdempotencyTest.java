package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
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
import java.util.List;
import java.util.UUID;
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
 * 컨슈머 재전달이 재고를 부풀리지 않는다 (실 PostgreSQL + Redis + Kafka 필요)
 *
 * <p>{@code insertAll} 은 쿠폰별로 커밋하므로, 그 뒤 Redis 상태 기록이나 결과 토픽 전송이 실패하면
 * 같은 배치가 다시 온다. 이미 들어간 행이 "미삽입" 으로 돌아와 재고를 +1 하면 DB 행은 있는데
 * Redis 재고만 늘어 초과 발급으로 이어진다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 컨슈머 재전달 멱등")
class CouponRedeliveryIdempotencyTest {

    private static final long QUANTITY = 10L;
    private static final int USERS = 3;
    private static final String EMAIL_PREFIX = "r115rd-";

    @Autowired
    private CouponAsyncIssueService service;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private CouponStockRedisRepository stockRepository;

    // 전용 컨텍스트를 만들면 캐시된 컨텍스트마다 Hikari 풀이 PostgreSQL 연결 상한을 갉아 먹는다
    // 스파이 대신 서비스의 중복 차단 로그로 "재고 반납 경로를 탔는가" 를 센다 - 그 로그는 rollbackStockOnly 바로 앞에서 찍힌다
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger serviceLogger;

    private Long couponId;
    private final List<Long> userIds = new ArrayList<>();
    private final List<CouponIssueRequestEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Coupon coupon = couponRepository.save(CouponCreateRequest.builder()
                .name("재전달 쿠폰").description("재전달 멱등용").quantity(QUANTITY).amount(1000)
                .type(CouponType.DISCOUNT).expireDate(LocalDate.now().plusDays(30)).build()
                .toEntity());
        couponId = coupon.getId();
        stockRepository.initStock(couponId, QUANTITY);

        for (int i = 0; i < USERS; i++) {
            UserJpaEntity user = userRepository.save(UserJpaEntity.builder()
                    .email(EMAIL_PREFIX + UUID.randomUUID().toString().substring(0, 8) + "@test.com").password("pw").name("재전달" + i)
                    .role(Role.ROLE_USER).grade(Grade.BRONZE).build());
            userIds.add(user.getId());
            // 접수 게이트를 통과한 상태를 그대로 만든다 - 재고 -1, 발급자 SET +1
            assertThat(stockRepository.tryIssue(couponId, user.getId()))
                    .isEqualTo(CouponStockRedisRepository.IssueResult.SUCCESS);
            events.add(new CouponIssueRequestEvent(UUID.randomUUID().toString(), couponId, user.getId(), true));
        }
        serviceLogger = (Logger) LoggerFactory.getLogger(CouponAsyncIssueService.class);
        logs.list.clear();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    private long 중복_차단_로그_수() {
        return logs.list.stream()
                .filter(e -> e.getFormattedMessage().contains("[COUPON_DUPLICATE_BLOCKED_BY_DB]")).count();
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
        logs.stop();
        redisTemplate.delete(List.of(CouponStockRedisRepository.stockKey(couponId),
                CouponStockRedisRepository.issuedUsersKey(couponId)));
        events.forEach(e -> redisTemplate.delete("coupon:issue:status:" + e.requestId()));
        couponIssueRepository.deleteAll(couponIssueRepository.findByCoupon_Id(couponId));
        couponRepository.deleteById(couponId);
        userRepository.deleteAllById(userIds);
    }

    @Test
    @DisplayName("같은 배치를 두 번 처리해도 재고 · 발급자 SET · DB 행 수는 1회 처리와 같다")
    void processBatch_같은_배치를_두번_처리해도_1회와_같다() {
        service.processBatch(events);
        long stockAfterFirst = stockRepository.getStock(couponId);
        long issuedAfterFirst = stockRepository.countIssuedUsers(couponId);
        long rowsAfterFirst = couponIssueRepository.countByCoupon_Id(couponId);
        assertThat(stockAfterFirst).isEqualTo(QUANTITY - USERS);

        // 재전달 - 커밋은 됐는데 뒤 단계 실패로 오프셋이 커밋되지 않은 상황
        service.processBatch(events);

        assertThat(stockRepository.getStock(couponId)).isEqualTo(stockAfterFirst);
        assertThat(stockRepository.countIssuedUsers(couponId)).isEqualTo(issuedAfterFirst);
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(rowsAfterFirst);
        // 재고 반납 경로가 한 번도 불리지 않아야 한다 - +1 이 곧 초과 발급의 씨앗이다
        assertThat(중복_차단_로그_수()).isZero();
        events.forEach(e -> assertThat(service.getStatus(e.requestId())).isEqualTo("SUCCESS"));
    }

    @Test
    @DisplayName("다른 requestId 로 같은 사용자가 오면 진짜 중복이라 재고를 되돌리고 실패로 남긴다")
    void processBatch_다른_requestId_는_진짜_중복이다() {
        service.processBatch(events);
        CouponIssueRequestEvent intruder = new CouponIssueRequestEvent(
                UUID.randomUUID().toString(), couponId, userIds.get(0), true);

        service.processBatch(List.of(intruder));

        assertThat(중복_차단_로그_수()).isEqualTo(1L);
        assertThat(service.getStatus(intruder.requestId())).isEqualTo("FAILED:COUPON_ALREADY_ISSUED");
        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(USERS);
        redisTemplate.delete("coupon:issue:status:" + intruder.requestId());
    }

    @Test
    @DisplayName("재처리를 다 쓴 배치 정리는 행이 있는 요청의 재고를 반납하지 않는다")
    void abandonBatch_행이_있는_요청은_반납하지_않고_없는_요청만_반납한다() {
        // 앞 두 건은 커밋됨, 세 번째 사용자는 커밋 전 - 정리 시점에 행이 없다
        service.processBatch(events.subList(0, 2));
        long stockBefore = stockRepository.getStock(couponId);
        long issuedBefore = stockRepository.countIssuedUsers(couponId);

        service.abandonBatch(events, new IllegalStateException("result topic down"));

        // 행이 없는 한 건만 되돌아온다: 재고 +1, 발급자 SET -1
        assertThat(stockRepository.getStock(couponId)).isEqualTo(stockBefore + 1);
        assertThat(stockRepository.countIssuedUsers(couponId)).isEqualTo(issuedBefore - 1);
        assertThat(service.getStatus(events.get(0).requestId())).isEqualTo("SUCCESS");
        assertThat(service.getStatus(events.get(1).requestId())).isEqualTo("SUCCESS");
        assertThat(service.getStatus(events.get(2).requestId())).isEqualTo("FAILED:SERVER_ERROR");
    }
}
