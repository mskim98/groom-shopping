package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 재고 대조·보정 배치")
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
    @DisplayName("게이트가 실제보다 좁으면 기대값으로 올린다")
    void reconcileCoupon_재고가_기대보다_적으면_올린다() {
        Coupon c = coupon(1L, 40L, true, LocalDate.now().plusDays(10));
        given(couponStockRedisRepository.getStock(1L)).willReturn(35L);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(60L);
        given(couponStockRedisRepository.countIssuedUsers(1L)).willReturn(60L);

        // 기대 = 40 + 60 - 60 = 40, 실제 35 → 5 만큼 좁아져 있다
        CouponStockReconciliationService.ReconcileReport report = service.reconcileCoupon(c);

        assertThat(report.expectedStock()).isEqualTo(40L);
        assertThat(report.correctedTo()).isEqualTo(40L);
        verify(couponStockRedisRepository).initStock(1L, 40L);
    }

    @Test
    @DisplayName("게이트가 느슨하면 내리지 않는다")
    void reconcileCoupon_재고가_기대보다_많으면_내리지_않는다() {
        Coupon c = coupon(1L, 40L, true, LocalDate.now().plusDays(10));
        given(couponStockRedisRepository.getStock(1L)).willReturn(45L);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(60L);
        given(couponStockRedisRepository.countIssuedUsers(1L)).willReturn(60L);

        CouponStockReconciliationService.ReconcileReport report = service.reconcileCoupon(c);

        assertThat(report.correctedTo()).isEqualTo(45L); // 손대지 않음
        verify(couponStockRedisRepository, never()).initStock(1L, 40L);
    }

    @Test
    @DisplayName("진행 중 예약은 기대값을 낮춰 재고를 되살리지 않는다")
    void reconcileCoupon_진행중_예약이_있으면_되살리지_않는다() {
        Coupon c = coupon(1L, 40L, true, LocalDate.now().plusDays(10));
        // 게이트는 65명을 예약했고 DB 확정은 60명 - 5건이 진행 중이다
        given(couponStockRedisRepository.getStock(1L)).willReturn(35L);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(60L);
        given(couponStockRedisRepository.countIssuedUsers(1L)).willReturn(65L);

        CouponStockReconciliationService.ReconcileReport report = service.reconcileCoupon(c);

        // 기대 = 40 + 60 - 65 = 35 = 실제. 보정하지 않는다
        assertThat(report.expectedStock()).isEqualTo(35L);
        verify(couponStockRedisRepository, never()).initStock(1L, 35L);
    }

    @Test
    @DisplayName("종료된 쿠폰은 DB 에 없는 예약을 회수한다")
    void reconcileCoupon_종료된_쿠폰의_고아예약을_회수한다() {
        // 종료된 쿠폰: 예약 3명(11·22·33) 중 DB 확정은 2명 → 33 이 고아다
        Coupon c = coupon(1L, 40L, false, LocalDate.now().plusDays(10));
        given(couponStockRedisRepository.getStock(1L)).willReturn(38L);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(2L);
        given(couponStockRedisRepository.countIssuedUsers(1L)).willReturn(3L);
        given(couponStockRedisRepository.getIssuedUsers(1L))
                .willReturn(Set.of("11", "22", "33"));
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of(11L, 22L));

        CouponStockReconciliationService.ReconcileReport report = service.reconcileCoupon(c);

        assertThat(report.orphansRemoved()).isEqualTo(1);
        verify(couponStockRedisRepository).removeIssuedUsers(1L, List.of(33L));
        // 고아를 뺀 뒤 기대 = 40 + 2 - 2 = 40. 실제 38 이므로 40 으로 올린다
        assertThat(report.expectedStock()).isEqualTo(40L);
        verify(couponStockRedisRepository).initStock(1L, 40L);
    }

    @Test
    @DisplayName("재고 키가 없는 쿠폰은 건너뛴다")
    void reconcileCoupon_재고키가_없으면_건너뛴다() {
        Coupon c = coupon(1L, 40L, true, LocalDate.now().plusDays(10));
        given(couponStockRedisRepository.getStock(1L)).willReturn(null);

        CouponStockReconciliationService.ReconcileReport report = service.reconcileCoupon(c);

        assertThat(report).isNull();
        verify(couponStockRedisRepository, never()).initStock(1L, 40L);
    }
}
