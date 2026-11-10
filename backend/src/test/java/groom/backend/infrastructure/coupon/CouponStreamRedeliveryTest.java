package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import groom.backend.application.coupon.CouponIssueBatchWriter;
import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import java.util.List;
import java.util.Map;
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
 * 재전달은 멱등이다 - 커밋 뒤 ack 전에 죽어 같은 항목이 다시 와도 행은 하나이고 재고는 되돌지 않는다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 스트림 재전달 멱등")
class CouponStreamRedeliveryTest {

    @Autowired
    private CouponStreamConsumer consumer;
    @Autowired
    private CouponIssueBatchWriter writer;
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
    private Long couponId;
    private Long userId;
    private String requestId;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        couponId = fixture.coupon(10);
        stockRepository.initStock(couponId, 10L);
        userId = fixture.user();
        requestId = fixture.requestId();
        stockRepository.enqueue(couponId, userId, requestId);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    @Test
    @DisplayName("같은 항목을 두 번 처리해도 행은 하나이고 예외 없이 SUCCESS 이며 항목은 ack 된다")
    void process_같은_항목을_두번_처리해도_행은_하나다() {
        consumer.ensureGroup(couponId);
        // 다른 인스턴스가 읽고 처리 중이다 - 항목이 PEL 에 올라가 있다
        fixture.readAs(couponId, "other-instance");
        MapRecord<String, Object, Object> record =
                redis.opsForStream().range(CouponStockRedisRepository.streamKey(couponId), Range.unbounded()).get(0);
        CouponStreamConsumer.StreamEntry entry = CouponStreamConsumer.StreamEntry.from(record);

        consumer.process(couponId, List.of(entry));
        assertThatCode(() -> consumer.process(couponId, List.of(entry))).doesNotThrowAnyException();

        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        assertThat(fixture.status(requestId)).isEqualTo("SUCCESS");
        assertThat(fixture.pendingCount(couponId)).isZero();
        // 두 번째 처리가 재고를 되돌리면 +1 씩 부푼다
        assertThat(stockRepository.getStock(couponId)).isEqualTo(9L);
    }

    @Test
    @DisplayName("커밋만 되고 ack 되지 못한 항목을 다시 읽어도 행은 하나이고 SUCCESS 로 마감된다")
    void pollCoupon_커밋_뒤_ack_전에_죽었어도_재전달이_흡수된다() {
        // 앞 인스턴스가 INSERT 를 커밋하고 ack · 상태 기록 전에 죽었다
        writer.insertAll(couponId, Map.of(userId, requestId));
        assertThat(fixture.status(requestId)).isEqualTo("WAITING");

        consumer.pollCoupon(couponId);

        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        assertThat(fixture.status(requestId)).isEqualTo("SUCCESS");
        assertThat(fixture.pendingCount(couponId)).isZero();
        assertThat(stockRepository.getStock(couponId)).isEqualTo(9L);
    }
}
