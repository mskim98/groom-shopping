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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * <p>process() 는 {@code issueCouponWithoutLock} 을 타므로 Redis 재고가 심겨 있으면 Lua 경로,
 * 없으면 {@code issueCouponInDbOnly} 폴백 경로로 갈린다. 두 경로 모두 DB 수량을 1 줄이므로
 * DB 수량만으로는 구분되지 않는다. 어느 경로를 탔는지는 <b>Redis 재고값</b>으로만 판별한다.
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

    @BeforeEach
    void setUp() {
        couponIssueRepository.deleteAll();
        couponRepository.deleteAll();
        userRepository.deleteAll();
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

    // Redis 재고를 심지 않은 상태 = tryIssue 가 NOT_INITIALIZED → issueCouponInDbOnly 폴백 경로
    // 폴백이 끊기지 않았음을 지키는 케이스다 (Lua 경로 검증은 아래 세 케이스가 맡는다)
    @Test
    @DisplayName("재고 미워밍업 시 DB 폴백으로 발급 - enqueue WAITING, process SUCCESS")
    void enqueueThenProcess_success() {
        // when - 비동기 발급 접수
        lastRequestId = couponAsyncIssueService.enqueue(couponId, userId);

        // then - 초기 상태 WAITING
        assertThat(couponAsyncIssueService.getStatus(lastRequestId)).isEqualTo("WAITING");

        // when - 컨슈머가 호출하는 처리 로직 직접 실행
        // 재고를 심지 않았으므로 접수 게이트가 NOT_INITIALIZED 로 통과시킨다 → gateReserved=false
        couponAsyncIssueService.process(new CouponIssueRequestEvent(lastRequestId, couponId, userId, false));

        // then - 상태 SUCCESS + DB 발급 + 수량 차감
        assertThat(couponAsyncIssueService.getStatus(lastRequestId)).isEqualTo("SUCCESS");
        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isNotEmpty();
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(DB_QUANTITY - 1);
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

        // then - 상태 SUCCESS + DB 발급 + DB 수량 차감
        assertThat(couponAsyncIssueService.getStatus(requestId)).isEqualTo("SUCCESS");
        assertThat(couponIssueRepository.findByCouponIdAndUserId(couponId, userId)).isPresent();
        assertThat(couponIssueRepository.findCouponIssueByUserId(userId)).hasSize(1);
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(DB_QUANTITY - 1);

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
        assertThatThrownBy(() -> couponAsyncIssueService.enqueue(couponId, userId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_OUT_OF_STOCK);

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
        assertThatThrownBy(() -> couponAsyncIssueService.enqueue(couponId, userId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);

        // findByCouponIdAndUserId 는 Optional 이라 중복 행이 생기면 조회 자체가 깨진다. 행 수로 직접 센다
        assertThat(couponIssueRepository.findCouponIssueByUserId(userId)).hasSize(1);

        // then - 재고는 한 번만 차감된다 (중복 요청이 차감 전에 막혔다는 근거)
        assertThat(couponStockRedisRepository.getStock(couponId)).isEqualTo(REDIS_STOCK - 1);
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(DB_QUANTITY - 1);
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
        assertThatThrownBy(() -> couponAsyncIssueService.enqueue(couponId, userId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);

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
        assertThatThrownBy(() -> couponAsyncIssueService.enqueue(couponId, userId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_OUT_OF_STOCK);

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
