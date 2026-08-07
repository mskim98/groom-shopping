package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis 발급자 SET 이 사라진 상황(이벤트 중 Redis 재시작)에서
 * DB 유니크 제약이 중복 발급을 실제로 막는지 검증한다
 */
// 클래스 레벨 @Transactional 을 두지 않는다
// persistIssuedCoupon 이 REQUIRES_NEW 라 테스트 트랜잭션이 커밋되지 않으면 새 트랜잭션에서 쿠폰이 보이지 않는다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 중복 발급 최후 방어선 통합 테스트")
class CouponIssueDuplicateGuardIntegrationTest {

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private SpringDataUserRepository userRepository;

    @Autowired
    private CouponStockRedisRepository couponStockRedisRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private User user;
    private Long couponId;

    @BeforeEach
    void setUp() {
        couponIssueRepository.deleteAll();
        couponRepository.deleteAll();
        userRepository.deleteAll();

        UserJpaEntity entity = userRepository.save(UserJpaEntity.builder()
                .email("dup@test.com")
                .password("pw")
                .name("중복테스터")
                .role(Role.ROLE_USER)
                .grade(Grade.BRONZE)
                .build());
        user = new User(
                entity.getId(),
                entity.getEmail(),
                entity.getPassword(),
                entity.getName(),
                entity.getRole(),
                entity.getGrade(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );

        Coupon coupon = couponRepository.save(CouponCreateRequest.builder()
                .name("중복 방어 쿠폰")
                .description("유니크 제약 검증")
                .quantity(100L)
                .amount(1000)
                .type(CouponType.DISCOUNT)
                .expireDate(LocalDate.now().plusDays(30))
                .build()
                .toEntity());
        couponId = coupon.getId();

        deleteKeys();
        couponStockRedisRepository.initStock(couponId, 100L);
    }

    @AfterEach
    void tearDown() {
        deleteKeys();
    }

    @Test
    @DisplayName("발급자 SET 이 비어도 두 번째 발급은 DB 제약이 막는다")
    void persistIssuedCoupon_발급자SET이_비어도_두번째는_ALREADY_ISSUED() {
        couponIssueService.persistIssuedCoupon(couponId, user);

        // 이벤트 중 Redis 재시작으로 발급자 SET 이 사라진 상황을 만든다
        redisTemplate.delete(CouponStockRedisRepository.issuedUsersKey(couponId));

        assertThatThrownBy(() -> couponIssueService.persistIssuedCoupon(couponId, user))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);

        assertThat(couponIssueRepository.findCouponIssueByUserId(user.getId())).hasSize(1);
    }

    private void deleteKeys() {
        redisTemplate.delete(List.of(
                CouponStockRedisRepository.stockKey(couponId),
                CouponStockRedisRepository.issuedUsersKey(couponId)));
    }
}
