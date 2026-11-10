package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.coupon.CouponStockRedisRepository.IssueResult;
import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 접수 Lua 한 번으로 판정 · 예약 · 스트림 적재 · 상태 기록이 끝난다 (실 Redis 필요)
 *
 * <p>SISMEMBER → GET → DECR → SADD → XADD → SET 이 한 스크립트라 중간에 끊기는 구간이 없다.
 * 재고만 깎이고 스트림에는 없는 요청, 스트림에는 있는데 명단에 없는 요청이 생기지 않는다는 것이 이 시험의 대상이다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 접수 Lua + 스트림 적재")
class CouponStreamEnqueueLuaTest {

    @Autowired
    private CouponStockRedisRepository stockRepository;
    @Autowired
    private CouponAsyncIssueService service;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;

    private CouponStreamFixture fixture;
    private Long couponId;
    private Long userId;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        couponId = fixture.coupon(10);
        userId = fixture.user();
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    private long streamLength() {
        Long size = redis.opsForStream().size(CouponStockRedisRepository.streamKey(couponId));
        return size == null ? 0L : size;
    }

    @Test
    @DisplayName("접수 한 건이면 재고 -1 · 명단 등록 · 스트림 1건(userId, requestId) · 상태 WAITING 이다")
    void enqueue_한건이면_재고_명단_스트림_상태가_모두_남는다() {
        stockRepository.initStock(couponId, 5L);
        String requestId = fixture.requestId();

        IssueResult result = stockRepository.enqueue(couponId, userId, requestId);

        assertThat(result).isEqualTo(IssueResult.SUCCESS);
        assertThat(stockRepository.getStock(couponId)).isEqualTo(4L);
        assertThat(stockRepository.getIssuedUsers(couponId)).containsExactly(String.valueOf(userId));
        List<MapRecord<String, Object, Object>> entries =
                redis.opsForStream().range(CouponStockRedisRepository.streamKey(couponId), Range.unbounded());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getValue())
                .containsEntry("userId", String.valueOf(userId))
                .containsEntry("requestId", requestId);
        assertThat(redis.opsForValue().get(CouponStockRedisRepository.statusKey(requestId))).isEqualTo("WAITING");
        // 상태 키에 만료가 걸려 있어야 폴링이 끝난 요청의 키가 남지 않는다
        assertThat(redis.getExpire(CouponStockRedisRepository.statusKey(requestId)))
                .isBetween(1L, Duration.ofHours(1).toSeconds());
    }

    @Test
    @DisplayName("같은 사용자의 두 번째 접수는 중복이고 재고 · 스트림 · 상태 어느 것도 늘지 않는다")
    void enqueue_같은_사용자는_중복이고_스트림에_싣지_않는다() {
        stockRepository.initStock(couponId, 5L);
        stockRepository.enqueue(couponId, userId, fixture.requestId());
        String second = fixture.requestId();

        IssueResult result = stockRepository.enqueue(couponId, userId, second);

        assertThat(result).isEqualTo(IssueResult.ALREADY_ISSUED);
        assertThat(stockRepository.getStock(couponId)).isEqualTo(4L);
        assertThat(streamLength()).isEqualTo(1L);
        assertThat(redis.hasKey(CouponStockRedisRepository.statusKey(second))).isFalse();
    }

    @Test
    @DisplayName("재고 0 이면 품절이고 스트림에 싣지 않는다")
    void enqueue_재고가_0이면_품절이고_스트림에_싣지_않는다() {
        stockRepository.initStock(couponId, 0L);
        String requestId = fixture.requestId();

        IssueResult result = stockRepository.enqueue(couponId, userId, requestId);

        assertThat(result).isEqualTo(IssueResult.OUT_OF_STOCK);
        assertThat(streamLength()).isZero();
        assertThat(stockRepository.getIssuedUsers(couponId)).isEmpty();
        assertThat(redis.hasKey(CouponStockRedisRepository.statusKey(requestId))).isFalse();
    }

    @Test
    @DisplayName("재고 키가 없으면 미초기화이고 스트림 키도 만들지 않는다")
    void enqueue_재고키가_없으면_미초기화이고_아무것도_만들지_않는다() {
        String requestId = fixture.requestId();

        IssueResult result = stockRepository.enqueue(couponId, userId, requestId);

        assertThat(result).isEqualTo(IssueResult.NOT_INITIALIZED);
        assertThat(redis.hasKey(CouponStockRedisRepository.streamKey(couponId))).isFalse();
        assertThat(redis.hasKey(CouponStockRedisRepository.stockKey(couponId))).isFalse();
    }

    @Test
    @DisplayName("서비스 접수는 requestId 를 돌려주고 Kafka 로 아무것도 보내지 않는다")
    void enqueue_서비스_접수는_카프카를_쓰지_않고_스트림에_싣는다() {
        stockRepository.initStock(couponId, 5L);

        CouponIssueResult result = service.enqueue(couponId, userId);

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.ACCEPTED);
        assertThat(streamLength()).isEqualTo(1L);
        assertThat(service.getStatus(result.requestId())).isEqualTo("WAITING");
        // 서비스가 만든 requestId 는 픽스처 목록에 없어 직접 지운다
        redis.delete(CouponStockRedisRepository.statusKey(result.requestId()));
        // 접수 서비스가 Kafka 클라이언트를 들고 있지 않다 - 전송 경로 자체가 없다
        assertThat(Arrays.stream(CouponAsyncIssueService.class.getDeclaredFields()).map(Field::getType)
                .map(Class::getName))
                .noneMatch(name -> name.startsWith("org.springframework.kafka") || name.startsWith("org.apache.kafka"));
    }
}
