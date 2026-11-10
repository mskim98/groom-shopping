package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

/**
 * Redis 소실 복구 - 복원값은 quantity 에서 DB 발급 수를 뺀 값이다
 *
 * <p>대기 중인 요청은 같은 Redis 의 스트림에 있어 재고 키와 함께 사라진다. 그래서 빼야 할 대기분이 없다.
 * 접수됐으나 확정되지 못한 요청은 재고 키와 함께 사라지므로 그 자리는 다시 열린다
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("쿠폰 재고 복구")
class CouponStockRecoveryServiceTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private CouponStockRedisRepository couponStockRedisRepository;

    private CouponStockRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new CouponStockRecoveryService(couponRepository, couponIssueRepository,
                couponStockRedisRepository);
    }

    private void 활성쿠폰_한건(long quantity) {
        Coupon c = Coupon.builder().id(1L).name("쿠폰").quantity(quantity).amount(1000)
                .type(CouponType.DISCOUNT).isActive(true)
                .expireDate(LocalDate.now().plusDays(10)).build();
        given(couponRepository.findActiveByIdGreaterThan(eq(0L), any(Pageable.class)))
                .willReturn(List.of(c));
    }

    @Test
    @DisplayName("재고 키가 사라지면 quantity − DB 발급 수를 심는다")
    void recoverAll_수량에서_발급_수를_빼고_심는다() {
        활성쿠폰_한건(10_000L);
        given(couponStockRedisRepository.getStock(1L)).willReturn(null);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(6_000L);
        given(couponStockRedisRepository.initStockIfAbsent(eq(1L), anyLong())).willReturn(true);

        service.recoverAll();

        // 10,000 − 6,000(확정) = 4,000. 대기 요청은 스트림과 함께 사라졌으므로 빼지 않는다
        verify(couponStockRedisRepository).initStockIfAbsent(1L, 4_000L);
    }

    @Test
    @DisplayName("발급자 명단을 DB 에서 다시 채운 뒤에 재고 키를 심는다")
    void recoverAll_명단을_먼저_채우고_재고를_심는다() {
        활성쿠폰_한건(10L);
        given(couponStockRedisRepository.getStock(1L)).willReturn(null);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(2L);
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of(7L, 8L));
        given(couponStockRedisRepository.initStockIfAbsent(eq(1L), anyLong())).willReturn(true);

        service.recoverAll();

        // 재고 키가 먼저 생기면 명단이 빈 동안 이미 받은 사용자가 접수를 다시 통과한다
        InOrder order = inOrder(couponStockRedisRepository);
        order.verify(couponStockRedisRepository).addIssuedUsers(1L, List.of(7L, 8L));
        order.verify(couponStockRedisRepository).initStockIfAbsent(1L, 8L);
    }

    @Test
    @DisplayName("복구가 도는 동안은 isRecovering 이 참이다 - 접수 게이트가 이 값을 읽는다")
    void recoverAll_도는_동안_복구중이다() {
        활성쿠폰_한건(10L);
        given(couponStockRedisRepository.getStock(1L)).willAnswer(invocation -> {
            assertThat(service.isRecovering()).isTrue();
            return null;
        });
        given(couponStockRedisRepository.initStockIfAbsent(eq(1L), anyLong())).willReturn(true);

        service.recoverAll();

        assertThat(service.isRecovering()).isFalse();
    }

    @Test
    @DisplayName("이미 넘었으면 0 으로 막고 알린다")
    void recoverAll_음수면_0으로_막는다() {
        활성쿠폰_한건(10_000L);
        given(couponStockRedisRepository.getStock(1L)).willReturn(null);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(10_200L);
        given(couponStockRedisRepository.initStockIfAbsent(eq(1L), anyLong())).willReturn(true);

        service.recoverAll();

        // 음수를 그대로 심으면 Lua 의 stock <= 0 분기가 받아 주지만, 얼마나 넘었는지가 사라진다
        verify(couponStockRedisRepository).initStockIfAbsent(1L, 0L);
    }

    @Test
    @DisplayName("살아 있는 재고 키는 덮지 않는다")
    void recoverAll_재고가_살아있으면_건너뛴다() {
        활성쿠폰_한건(10_000L);
        given(couponStockRedisRepository.getStock(1L)).willReturn(4_200L);

        service.recoverAll();

        // 덮으면 게이트 통과분이 되살아난다 - 워밍업이 SETNX 인 것과 같은 이유다
        verify(couponStockRedisRepository, never()).initStockIfAbsent(anyLong(), anyLong());
    }

    @Test
    @DisplayName("복구가 끝나면 접수 차단이 풀린다")
    void recoverAll_끝나면_차단이_풀린다() {
        활성쿠폰_한건(10_000L);

        assertThat(service.isRecovering()).isFalse();
        service.recoverAll();
        // 풀리지 않으면 그 인스턴스는 영영 요청을 받지 않는다
        assertThat(service.isRecovering()).isFalse();
    }
}
