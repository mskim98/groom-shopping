package groom.backend.application.coupon;

import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.infrastructure.coupon.CouponStreamConsumer;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 스트림 시험이 쓰는 쿠폰 · 사용자 · Redis 키 준비와 정리
 *
 * <p>시험마다 새 쿠폰(IDENTITY 키)과 새 사용자를 만들고 {@link #cleanup()} 이 자기가 만든 것만 지운다.
 * deleteAll() 을 쓰지 않는다 - 같은 DB 의 남의 행을 지우면 남의 시험이 깨진다
 */
public class CouponStreamFixture {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final SpringDataUserRepository userRepository;
    private final StringRedisTemplate redis;

    private final List<Long> couponIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<String> requestIds = new ArrayList<>();

    public CouponStreamFixture(CouponRepository couponRepository, CouponIssueRepository couponIssueRepository,
                               SpringDataUserRepository userRepository, StringRedisTemplate redis) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.userRepository = userRepository;
        this.redis = redis;
    }

    /** 진행 중인 쿠폰을 만든다. Redis 재고는 심지 않는다 */
    public Long coupon(long quantity) {
        return coupon(quantity, true);
    }

    public Long coupon(long quantity, boolean active) {
        Coupon saved = couponRepository.save(Coupon.builder()
                .name("스트림 시험 쿠폰 " + UUID.randomUUID()).description("스트림 시험용").quantity(quantity)
                .amount(1000).type(CouponType.DISCOUNT).isActive(active)
                .expireDate(LocalDate.now().plusDays(30)).build());
        couponIds.add(saved.getId());
        return saved.getId();
    }

    public Long user() {
        return users(1).get(0);
    }

    public List<Long> users(int count) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        List<Long> created = userRepository.saveAll(IntStream.range(0, count)
                        .mapToObj(i -> UserJpaEntity.builder()
                                .email("stream-" + tag + "-" + i + "@test.com").password("pw").name("스트림시험")
                                .role(Role.ROLE_USER).grade(Grade.BRONZE).build())
                        .toList())
                .stream().map(UserJpaEntity::getId).toList();
        userIds.addAll(created);
        return created;
    }

    /** 다른 컨슈머 이름으로 새 항목을 읽어 그 이름의 PEL 에 올린다. 죽은 인스턴스를 흉내 낸다 */
    public List<String> readAs(Long couponId, String consumerName) {
        List<MapRecord<String, Object, Object>> read = redis.opsForStream().read(
                Consumer.from(CouponStreamConsumer.GROUP, consumerName),
                StreamReadOptions.empty().count(1000),
                StreamOffset.create(CouponStockRedisRepository.streamKey(couponId), ReadOffset.lastConsumed()));
        return read == null ? List.of() : read.stream().map(r -> r.getId().getValue()).toList();
    }

    /** 항목을 다른 컨슈머 이름으로 넘겨 배달 횟수를 1 올린다(XCLAIM 은 횟수를 올린다) */
    public void claimAs(Long couponId, String consumerName, String entryId) {
        redis.opsForStream().claim(CouponStockRedisRepository.streamKey(couponId), CouponStreamConsumer.GROUP,
                consumerName, Duration.ZERO, RecordId.of(entryId));
    }

    /** 여러 항목을 한 번에 다른 컨슈머 이름으로 넘긴다. 넘긴 시각부터 유휴 시간이 다시 0 부터 센다 */
    public void claimAllAs(Long couponId, String consumerName, List<String> entryIds) {
        redis.opsForStream().claim(CouponStockRedisRepository.streamKey(couponId), CouponStreamConsumer.GROUP,
                consumerName, Duration.ZERO, entryIds.stream().map(RecordId::of).toArray(RecordId[]::new));
    }

    /** 그룹의 PEL 에 남은 항목 수. 그룹이 없으면 0 이다 */
    public long pendingCount(Long couponId) {
        try {
            PendingMessagesSummary summary = redis.opsForStream()
                    .pending(CouponStockRedisRepository.streamKey(couponId), CouponStreamConsumer.GROUP);
            return summary == null ? 0L : summary.getTotalPendingMessages();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    public String status(String requestId) {
        return redis.opsForValue().get(CouponStockRedisRepository.statusKey(requestId));
    }

    public String requestId() {
        String id = UUID.randomUUID().toString();
        requestIds.add(id);
        return id;
    }

    /** 이 픽스처가 만든 행과 키만 지운다. 발급 행이 사용자를 참조하므로 쿠폰 · 사용자 순서다 */
    public void cleanup() {
        for (Long couponId : couponIds) {
            couponIssueRepository.deleteAll(couponIssueRepository.findByCoupon_Id(couponId));
            couponRepository.findById(couponId).ifPresent(couponRepository::delete);
            redis.delete(List.of(CouponStockRedisRepository.stockKey(couponId),
                    CouponStockRedisRepository.issuedUsersKey(couponId),
                    CouponStockRedisRepository.streamKey(couponId),
                    CouponStockRedisRepository.dlqKey(couponId)));
        }
        userRepository.deleteAllById(userIds);
        redis.delete(requestIds.stream().map(CouponStockRedisRepository::statusKey).toList());
        couponIds.clear();
        userIds.clear();
        requestIds.clear();
    }
}
