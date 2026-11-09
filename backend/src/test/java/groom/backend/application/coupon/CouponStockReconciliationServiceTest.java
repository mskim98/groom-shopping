package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.kafka.KafkaLagReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Pageable;

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
    @Mock
    private KafkaLagReader kafkaLagReader;

    private CouponStockReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new CouponStockReconciliationService(couponRepository, couponIssueRepository,
                couponStockRedisRepository, redissonClient, kafkaLagReader, new SimpleMeterRegistry());
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
    @DisplayName("컨슈머 랙이 0 이면 고아 예약을 회수한다")
    void reconcileCoupon_랙이_0이면_고아예약을_회수한다() {
        고아_예약이_하나_있는_종료_쿠폰();
        명단과_확정_행을_읽는다();
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(0L);

        assertThat(service.reconcileCoupon(coupon(1L, 40L, false, LocalDate.now().plusDays(10))).orphansRemoved())
                .isEqualTo(1);

        verify(couponStockRedisRepository).removeIssuedUsers(1L, List.of(3L));
    }

    @Test
    @DisplayName("랙을 먼저 읽고 0 일 때만 발급자 명단과 확정 행을 읽는다")
    void reconcileCoupon_랙을_명단과_확정_행보다_먼저_읽는다() {
        고아_예약이_하나_있는_종료_쿠폰();
        명단과_확정_행을_읽는다();
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(0L);

        service.reconcileCoupon(coupon(1L, 40L, false, LocalDate.now().plusDays(10)));

        // 확정 행을 먼저 읽고 랙을 나중에 읽으면, 그 사이에 커밋된 요청이 "확정 행 목록에 없는데 랙은 0" 으로 보여
        // 방금 확정된 사용자가 명단에서 지워진다 - 게이트가 그를 다시 통과시켜 같은 쿠폰을 한 번 더 받는다
        InOrder order = inOrder(kafkaLagReader, couponStockRedisRepository, couponIssueRepository);
        order.verify(kafkaLagReader).lagOf(anyString(), anyString());
        order.verify(couponStockRedisRepository).getIssuedUsers(1L);
        order.verify(couponIssueRepository).findUserIdsByCouponId(1L);
    }

    @Test
    @DisplayName("컨슈머 랙이 남아 있으면 명단도 확정 행도 읽지 않고 회수하지 않는다")
    void reconcileCoupon_랙이_남아있으면_아무것도_읽지_않는다() {
        고아_예약이_하나_있는_종료_쿠폰();
        // 종료 시각이 지나도 요청 토픽에 아직 소비되지 않은 요청이 있다 - 그 사용자의 행은 곧 들어온다
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(7L);

        assertThat(service.reconcileCoupon(coupon(1L, 40L, false, LocalDate.now().plusDays(10))).orphansRemoved())
                .isZero();

        verify(couponStockRedisRepository, never()).getIssuedUsers(anyLong());
        verify(couponIssueRepository, never()).findUserIdsByCouponId(anyLong());
        // 지우면 곧 확정될 사용자가 게이트를 다시 통과해 같은 쿠폰을 한 번 더 받는다
        verify(couponStockRedisRepository, never()).removeIssuedUsers(anyLong(), anyCollection());
    }

    @Test
    @DisplayName("컨슈머 랙을 읽지 못하면 명단을 읽지 않고 회수하지 않는다")
    void reconcileCoupon_랙을_못_읽으면_회수하지_않는다() {
        고아_예약이_하나_있는_종료_쿠폰();
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(-1L);

        service.reconcileCoupon(coupon(1L, 40L, false, LocalDate.now().plusDays(10)));

        verify(couponStockRedisRepository, never()).getIssuedUsers(anyLong());
        verify(couponStockRedisRepository, never()).removeIssuedUsers(anyLong(), anyCollection());
    }

    @Test
    @DisplayName("마감도 랙이 남아 있으면 고아 예약을 회수하지 않고 발급 수는 굳힌다")
    void closeCoupon_랙이_남아있으면_회수를_미루고_굳힌다() {
        고아_예약이_하나_있는_종료_쿠폰();
        given(couponRepository.findById(1L)).willReturn(Optional.of(coupon(1L, 40L, false, LocalDate.now().plusDays(10))));
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(3L);

        service.closeCoupon(1L);

        verify(couponStockRedisRepository, never()).removeIssuedUsers(anyLong(), anyCollection());
        verify(couponRepository).freezeIssuedCount(eq(1L), anyLong());
    }

    @Test
    @DisplayName("종료된 쿠폰 3 개를 훑는 한 번의 대조 실행은 컨슈머 랙을 한 번만 읽는다")
    void reconcileAll_랙을_실행당_한_번만_읽는다() {
        종료_쿠폰_3개와_각각의_고아_예약(3);
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(0L);

        service.reconcileAll();

        // lagOf 는 호출마다 AdminClient 를 새로 만들고 5 초 제한을 건다. 랙은 쿠폰이 아니라 토픽 · 그룹 단위라
        // 쿠폰마다 읽을 이유가 없고, 종료된 쿠폰은 새 게이트 통과가 없어 실행 시작 시점의 0 이 실행 내내 유효하다
        verify(kafkaLagReader, times(1)).lagOf(anyString(), anyString());
        verify(couponStockRedisRepository, times(3)).removeIssuedUsers(anyLong(), anyCollection());
    }

    @Test
    @DisplayName("랙이 남아 있으면 쿠폰 3 개 모두 회수를 건너뛰고 랙은 한 번만 읽는다")
    void reconcileAll_랙이_남아있으면_전부_건너뛰고_한_번만_읽는다() {
        종료_쿠폰_3개와_각각의_고아_예약(3);
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(4L);

        service.reconcileAll();

        verify(kafkaLagReader, times(1)).lagOf(anyString(), anyString());
        verify(couponStockRedisRepository, never()).getIssuedUsers(anyLong());
        verify(couponStockRedisRepository, never()).removeIssuedUsers(anyLong(), anyCollection());
    }

    private void 종료_쿠폰_3개와_각각의_고아_예약(int count) {
        // 페이지 상한은 @Value 로 주입되는 값이라 단위 테스트에서는 직접 넣는다
        org.springframework.test.util.ReflectionTestUtils.setField(service, "batchSize", 500);
        List<Coupon> coupons = new java.util.ArrayList<>();
        for (long id = 1; id <= count; id++) {
            coupons.add(coupon(id, 40L, false, LocalDate.now().plusDays(10)));
        }
        given(couponRepository.findAllByIdGreaterThanOrderById(anyLong(), any(Pageable.class))).willReturn(coupons);
        given(couponStockRedisRepository.getStock(anyLong())).willReturn(35L);
        given(couponIssueRepository.countByCoupon_Id(anyLong())).willReturn(2L);
        given(couponStockRedisRepository.countIssuedUsers(anyLong())).willReturn(3L);
        // 랙이 남은 케이스에서는 아래 두 스텁이 쓰이지 않는다
        org.mockito.Mockito.lenient().when(couponStockRedisRepository.getIssuedUsers(anyLong()))
                .thenReturn(Set.of("1", "2", "3"));
        org.mockito.Mockito.lenient().when(couponIssueRepository.findUserIdsByCouponId(anyLong()))
                .thenReturn(List.of(1L, 2L));
    }

    // 재고 키와 개수 조회까지는 랙과 무관하게 읽는다
    private void 고아_예약이_하나_있는_종료_쿠폰() {
        given(couponStockRedisRepository.getStock(1L)).willReturn(35L);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(2L);
        given(couponStockRedisRepository.countIssuedUsers(1L)).willReturn(3L);
    }

    // 명단에 1, 2, 3 이 있고 확정 행은 1, 2 뿐이다 - 3 은 아직 저장되지 않았거나 영영 저장되지 않을 예약이다
    // 랙이 0 일 때만 읽히므로 랙이 남은 케이스에서는 스텁하지 않는다
    private void 명단과_확정_행을_읽는다() {
        given(couponStockRedisRepository.getIssuedUsers(1L)).willReturn(Set.of("1", "2", "3"));
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of(1L, 2L));
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
