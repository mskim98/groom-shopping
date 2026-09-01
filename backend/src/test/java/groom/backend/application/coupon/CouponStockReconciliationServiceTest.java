package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

/**
 * 대조는 종료된 쿠폰에만 적용하고, 재고 키는 고쳐 쓰지 않는다
 *
 * <p>주기 실행을 없앤 근거를 이 단언들이 고정한다 - 이벤트 진행 중에는
 * 게이트를 통과했는데 아직 저장되지 않은 건과 되돌려야 할 고아가 겉으로 완전히 같다
 * 그 상태에서 되돌리면 남의 자리를 뺏는다
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 재고 대조·마감")
class CouponStockReconciliationServiceTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private CouponStockRedisRepository couponStockRedisRepository;
    @Mock
    private RedissonClient redissonClient;

    private CouponStockReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new CouponStockReconciliationService(couponRepository, couponIssueRepository,
                couponStockRedisRepository, redissonClient, new SimpleMeterRegistry());
    }

    private Coupon coupon(long id, long quantity, boolean active, LocalDate expire) {
        return Coupon.builder().id(id).name("쿠폰").quantity(quantity).amount(1000)
                .type(CouponType.DISCOUNT).isActive(active).expireDate(expire).build();
    }

    @Test
    @DisplayName("진행 중인 쿠폰은 손대지 않는다")
    void reconcileCoupon_진행중이면_건너뛴다() {
        Coupon c = coupon(1L, 40L, true, LocalDate.now().plusDays(10));

        assertThat(service.reconcileCoupon(c)).isNull();

        // 재고 키를 읽지도 않는다. 읽고 판단하는 순간 "고칠까" 를 고민하게 된다
        verify(couponStockRedisRepository, never()).getStock(anyLong());
        verify(couponStockRedisRepository, never()).initStock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("재고 키가 없는 쿠폰은 건너뛴다")
    void reconcileCoupon_재고키가_없으면_건너뛴다() {
        Coupon c = coupon(1L, 40L, false, LocalDate.now().plusDays(10));
        given(couponStockRedisRepository.getStock(1L)).willReturn(null);

        assertThat(service.reconcileCoupon(c)).isNull();
        // 여기서 심으면 워밍업의 SETNX 규칙을 우회하게 된다
        verify(couponStockRedisRepository, never()).initStock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("종료된 쿠폰은 DB 에 없는 예약을 회수한다")
    void reconcileCoupon_종료쿠폰의_고아예약을_회수한다() {
        Coupon c = coupon(1L, 40L, false, LocalDate.now().plusDays(10));
        given(couponStockRedisRepository.getStock(1L)).willReturn(35L);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(60L);
        given(couponStockRedisRepository.countIssuedUsers(1L)).willReturn(60L);
        given(couponStockRedisRepository.getIssuedUsers(1L))
                .willReturn(Set.of("1", "2", "3"));
        // 3 번은 명단에만 있고 확정되지 않았다 - 이벤트가 끝났으므로 처리 중일 수 없다
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of(1L, 2L));

        CouponStockReconciliationService.ReconcileReport report = service.reconcileCoupon(c);

        assertThat(report.orphansRemoved()).isEqualTo(1);
        verify(couponStockRedisRepository).removeIssuedUsers(1L, List.of(3L));
    }

    @Test
    @DisplayName("어긋나도 재고 키는 고치지 않고 기록만 남긴다")
    void reconcileCoupon_어긋나도_고치지_않는다() {
        Coupon c = coupon(1L, 40L, false, LocalDate.now().plusDays(10));
        given(couponStockRedisRepository.getStock(1L)).willReturn(35L);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(60L);
        given(couponStockRedisRepository.countIssuedUsers(1L)).willReturn(60L);
        given(couponStockRedisRepository.getIssuedUsers(1L)).willReturn(Set.of());

        CouponStockReconciliationService.ReconcileReport report = service.reconcileCoupon(c);

        // 기대 = 40 + 60 - 60 = 40, 실제 35 로 어긋나 있다
        assertThat(report.expectedStock()).isEqualTo(40L);
        assertThat(report.correctedTo()).isEqualTo(35L);
        // 고치면 그 회차의 마지막 상태를 읽어 판정하는 쪽이 사후에 만들어진 값을 보게 된다
        verify(couponStockRedisRepository, never()).initStock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("마감은 확정 발급 수를 한 번 굳힌다")
    void closeCoupon_발급수를_굳힌다() {
        Coupon c = coupon(1L, 100L, false, LocalDate.now().plusDays(10));
        given(couponRepository.findById(1L)).willReturn(Optional.of(c));
        given(couponStockRedisRepository.getStock(1L)).willReturn(null);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(100L);

        assertThat(service.closeCoupon(1L)).isEqualTo(100L);

        // 이후로는 값이 변하지 않으므로 리포트가 매번 셀 이유가 없다
        verify(couponRepository).freezeIssuedCount(1L, 100L);
    }

    @Test
    @DisplayName("발행 수량을 넘겼으면 굳히되 되돌리지는 않는다")
    void closeCoupon_초과발급이면_경보만_낸다() {
        Coupon c = coupon(1L, 100L, false, LocalDate.now().plusDays(10));
        given(couponRepository.findById(1L)).willReturn(Optional.of(c));
        given(couponStockRedisRepository.getStock(1L)).willReturn(null);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(103L);

        assertThat(service.closeCoupon(1L)).isEqualTo(103L);

        // 회수하려면 "누구 것을 뺏을지" 를 정해야 하는데 그것은 시스템이 판단할 문제가 아니다
        // 원인을 모른 채 고치면 조사에 필요한 상태가 사라진다
        verify(couponRepository).freezeIssuedCount(1L, 103L);
        verify(couponIssueRepository, never()).deleteAll();
    }
}
