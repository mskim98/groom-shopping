package groom.backend.infrastructure.coupon;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 재고 워밍업 러너")
class CouponStockWarmUpRunnerTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private CouponStockRedisRepository couponStockRedisRepository;

    @Test
    @DisplayName("재고는 SETNX 로 심고 발급자 SET 은 DB 확정분으로 채운다")
    void run_재고는_SETNX로_발급자SET은_DB에서_복원한다() {
        Coupon coupon = Coupon.builder()
                .id(1L).name("쿠폰").quantity(50L).amount(1000)
                .type(CouponType.DISCOUNT).isActive(true)
                .expireDate(LocalDate.now().plusDays(30)).build();
        given(couponRepository.findActiveByIdGreaterThan(anyLong(), any(Pageable.class)))
                .willReturn(List.of(coupon), List.of());
        given(couponStockRedisRepository.initStockIfAbsent(1L, 50L)).willReturn(true);
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of(11L, 22L));

        CouponStockWarmUpRunner runner = new CouponStockWarmUpRunner(
                couponRepository, couponIssueRepository, couponStockRedisRepository);
        ReflectionTestUtils.setField(runner, "batchSize", 1);

        runner.run(null);

        // SETNX 여야 한다 - SET 으로 덮으면 커밋 전 요청 수만큼 재고가 되살아난다
        verify(couponStockRedisRepository).initStockIfAbsent(1L, 50L);
        // 발급자 SET 은 합집합으로 채운다 - 지우고 다시 만들면 진행 중 예약이 사라진다
        verify(couponStockRedisRepository).addIssuedUsers(1L, List.of(11L, 22L));
    }
}
