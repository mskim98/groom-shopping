package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.kafka.KafkaLagReader;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

/**
 * Redis 소실 복구 - 랙을 빼는 것이 이 계산의 전부다
 *
 * <p>{@code COUNT(coupon_issue)} 는 확정된 것만 세므로, 게이트는 통과했지만 아직 저장되지 않은 요청이 빠진다
 * 그만큼 재고를 많게 계산하고, 많게 계산한 재고는 그대로 초과 발급이 된다
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
    @Mock
    private KafkaLagReader kafkaLagReader;

    private CouponStockRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new CouponStockRecoveryService(couponRepository, couponIssueRepository,
                couponStockRedisRepository, kafkaLagReader);
    }

    private void 활성쿠폰_한건(long quantity) {
        Coupon c = Coupon.builder().id(1L).name("쿠폰").quantity(quantity).amount(1000)
                .type(CouponType.DISCOUNT).isActive(true)
                .expireDate(LocalDate.now().plusDays(10)).build();
        given(couponRepository.findActiveByIdGreaterThan(eq(0L), any(Pageable.class)))
                .willReturn(List.of(c));
    }

    @Test
    @DisplayName("복원값에서 랙을 뺀다")
    void recoverAll_랙만큼_빼고_심는다() {
        활성쿠폰_한건(10_000L);
        given(couponStockRedisRepository.getStock(1L)).willReturn(null);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(6_000L);
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(1_500L);
        given(couponStockRedisRepository.initStockIfAbsent(eq(1L), anyLong())).willReturn(true);

        service.recoverAll();

        // 10,000 − 6,000(확정) − 1,500(비행 중) = 2,500
        // 랙을 빼지 않으면 4,000 을 심어 비행 중인 1,500 만큼이 그대로 초과 발급이 된다
        verify(couponStockRedisRepository).initStockIfAbsent(1L, 2_500L);
    }

    @Test
    @DisplayName("랙을 읽지 못하면 복원하지 않는다")
    void recoverAll_랙을_못_읽으면_멈춘다() {
        활성쿠폰_한건(10_000L);
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(-1L);

        service.recoverAll();

        // 못 읽은 것을 0 으로 치고 복원하면 비행 중인 건수만큼 재고가 많아진다
        // 재고가 많아지는 것은 곧 초과 발급이므로 멈춘 채 사람을 부른다
        verify(couponStockRedisRepository, never()).initStockIfAbsent(anyLong(), anyLong());
    }

    @Test
    @DisplayName("이미 넘었으면 0 으로 막고 알린다")
    void recoverAll_음수면_0으로_막는다() {
        활성쿠폰_한건(10_000L);
        given(couponStockRedisRepository.getStock(1L)).willReturn(null);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(10_200L);
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(0L);
        given(couponStockRedisRepository.initStockIfAbsent(eq(1L), anyLong())).willReturn(true);

        service.recoverAll();

        // 음수를 그대로 심으면 Lua 의 stock <= 0 분기가 받아 주지만, 얼마나 넘었는지가 사라진다
        verify(couponStockRedisRepository).initStockIfAbsent(1L, 0L);
    }

    @Test
    @DisplayName("살아 있는 재고 키는 덮지 않는다")
    void recoverAll_재고가_살아있으면_건너뛴다() {
        활성쿠폰_한건(10_000L);
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(0L);
        given(couponStockRedisRepository.getStock(1L)).willReturn(4_200L);

        service.recoverAll();

        // 덮으면 게이트 통과분이 되살아난다 - 워밍업이 SETNX 인 것과 같은 이유다
        verify(couponStockRedisRepository, never()).initStockIfAbsent(anyLong(), anyLong());
    }

    @Test
    @DisplayName("복구가 끝나면 접수 차단이 풀린다")
    void recoverAll_끝나면_차단이_풀린다() {
        활성쿠폰_한건(10_000L);
        given(kafkaLagReader.lagOf(anyString(), anyString())).willReturn(-1L);

        assertThat(service.isRecovering()).isFalse();
        service.recoverAll();
        // 풀리지 않으면 그 인스턴스는 영영 요청을 받지 않는다
        assertThat(service.isRecovering()).isFalse();
    }
}
