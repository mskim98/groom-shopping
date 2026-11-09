package groom.backend.infrastructure.coupon;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import groom.backend.application.coupon.CouponStockRecoveryService;
import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
    @Mock
    private CouponStockRecoveryService couponStockRecoveryService;

    private Coupon 쿠폰(long quantity) {
        return Coupon.builder()
                .id(1L).name("쿠폰").quantity(quantity).amount(1000)
                .type(CouponType.DISCOUNT).isActive(true)
                .expireDate(LocalDate.now().plusDays(30)).build();
    }

    private CouponStockWarmUpRunner 러너(Coupon coupon) {
        given(couponRepository.findActiveByIdGreaterThan(anyLong(), any(Pageable.class)))
                .willReturn(List.of(coupon), List.of());
        CouponStockWarmUpRunner runner = new CouponStockWarmUpRunner(
                couponRepository, couponIssueRepository, couponStockRedisRepository, couponStockRecoveryService);
        ReflectionTestUtils.setField(runner, "batchSize", 1);
        return runner;
    }

    @Test
    @DisplayName("재고 키가 없으면 quantity 가 아니라 quantity − DB 발급 수를 심는다")
    void run_재고키_부재면_발행수량에서_발급수를_뺀_값을_심는다() {
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(20L);
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of(11L, 22L));
        given(couponStockRedisRepository.initStockIfAbsent(anyLong(), anyLong())).willReturn(true);

        러너(쿠폰(50L)).run(null);

        // V15 이후 quantity 는 고정된 총 발행량이다. 50 을 그대로 심으면 이미 나간 20 장이 되살아난다
        verify(couponStockRedisRepository).initStockIfAbsent(1L, 30L);
        verify(couponStockRedisRepository, never()).initStockIfAbsent(1L, 50L);
    }

    @Test
    @DisplayName("발급자 SET 을 먼저 복원하고 그 다음에 재고 키를 연다")
    void run_발급자_SET_복원이_재고_키보다_앞선다() {
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(2L);
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of(11L, 22L));
        given(couponStockRedisRepository.initStockIfAbsent(anyLong(), anyLong())).willReturn(true);

        러너(쿠폰(50L)).run(null);

        // 재고 키가 먼저 생기면 SET 이 비어 있는 동안 이미 받은 사용자가 게이트를 다시 통과한다
        InOrder order = inOrder(couponStockRedisRepository);
        order.verify(couponStockRedisRepository).addIssuedUsers(1L, List.of(11L, 22L));
        order.verify(couponStockRedisRepository).initStockIfAbsent(1L, 48L);
    }

    @Test
    @DisplayName("DB 발급 수가 발행 수량 이상이면 음수가 아니라 0 을 심는다")
    void run_발급수가_발행수량_이상이면_0을_심는다() {
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(55L);
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of());
        given(couponStockRedisRepository.initStockIfAbsent(anyLong(), anyLong())).willReturn(true);

        러너(쿠폰(50L)).run(null);

        verify(couponStockRedisRepository).initStockIfAbsent(1L, 0L);
    }
}
