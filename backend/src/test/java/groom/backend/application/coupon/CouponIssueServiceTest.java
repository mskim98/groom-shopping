package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.entity.CouponIssue;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.policy.DiscountPolicyFactory;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 발급 실패 사유가 서로 다른 ErrorCode 로 갈라지는지 고정한다
 *
 * <p>진짜 품절·DB 수량 소진·경합 실패가 한 코드로 뭉쳐 있으면
 * 실패 집계에서 "재고가 남았는데 실패한 건"과 "정말 소진된 건"을 나눌 수 없다
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CouponIssueService 발급 실패 사유 매핑")
class CouponIssueServiceTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private DiscountPolicyFactory discountPolicyFactory;
    @Mock
    private CacheManager couponCacheManager;
    @Mock
    private RedisTemplate<String, CouponIssueResponse> couponCacheTemplate;
    @Mock
    private CouponStockRedisRepository couponStockRedisRepository;
    @Mock
    private ObjectProvider<CouponIssueService> selfProvider;

    private CouponIssueService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new CouponIssueService(couponRepository, couponIssueRepository,
                discountPolicyFactory, couponCacheManager, couponCacheTemplate,
                couponStockRedisRepository, selfProvider);
        user = new User(7L, "u@test.com", "pw", "테스터",
                Role.ROLE_USER, Grade.BRONZE, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("Redis 는 통과시켰는데 DB 수량이 0이면 저장소 불일치로 구분된다")
    void persistIssuedCoupon_DB수량이0이면_COUPON_STORE_MISMATCH() {
        Coupon coupon = Coupon.builder()
                .id(1L).name("쿠폰").quantity(0L).amount(1000)
                .type(CouponType.DISCOUNT).isActive(true)
                .expireDate(LocalDate.now().plusDays(30)).build();
        given(couponRepository.findById(1L)).willReturn(Optional.of(coupon));
        given(couponIssueRepository.saveAndFlush(any(CouponIssue.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(couponRepository.decreaseQuantityAtomically(1L)).willReturn(0);

        assertThatThrownBy(() -> service.persistIssuedCoupon(1L, user))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_STORE_MISMATCH);
    }

    @Test
    @DisplayName("두 코드는 서로 다른 값이어야 측정에서 구분된다")
    void errorCode_경합실패와_진짜품절은_다른_코드다() {
        assertThat(ErrorCode.COUPON_ISSUE_CONTENTION)
                .isNotEqualTo(ErrorCode.COUPON_OUT_OF_STOCK);
        assertThat(ErrorCode.COUPON_STORE_MISMATCH)
                .isNotEqualTo(ErrorCode.COUPON_OUT_OF_STOCK);
    }
}
