package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.application.coupon.strategy.DbLockIssueStrategy;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.coupon.CouponIssueHealthIndicator;
import groom.backend.infrastructure.coupon.CouponStockWarmUpRunner;
import groom.backend.infrastructure.kafka.stream.CouponDelayConsumer;
import groom.backend.infrastructure.kafka.stream.CouponDelayEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 복구 코드가 운영 경로에 연결돼 있다
 *
 * <p>recoverAll · reconcileWithLock · closeCoupon 은 단위 테스트만 호출자였다. 호출자가 없는 복구 코드는
 * "3단 복구" 라는 문서 속 문장일 뿐 Redis 가 비워진 날 아무것도 하지 않는다
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("쿠폰 복구 연결")
class CouponRecoveryWiringTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private CouponStockRedisRepository stockRepository;
    @Mock
    private CouponStockRecoveryService recoveryService;

    private Coupon 쿠폰() {
        return Coupon.builder().id(1L).name("쿠폰").quantity(100L).amount(1000)
                .type(CouponType.DISCOUNT).isActive(true)
                .expireDate(LocalDate.now().plusDays(10)).build();
    }

    @BeforeEach
    void setUp() {
        // 쿠폰 한 건짜리 페이지 - 크기가 페이지 상한보다 작으므로 어느 쪽 스캔도 한 번에 끝난다
        given(couponRepository.findActiveByIdGreaterThan(anyLong(), any(Pageable.class)))
                .willReturn(List.of(쿠폰()));
    }

    @Test
    @DisplayName("시작 시 워밍업이 재고 키 부재 복구(recoverIfStockMissing → recoverAll)를 먼저 돌린다")
    void warmUp_시작하면_재고_키_부재_복구를_먼저_돌린다() {
        CouponStockWarmUpRunner runner = new CouponStockWarmUpRunner(
                couponRepository, couponIssueRepository, stockRepository, recoveryService);
        ReflectionTestUtils.setField(runner, "batchSize", 1000);

        runner.run(null);

        // quantity − DB 발급 수로 심는 경로다. 복구가 심은 키는 이어지는 워밍업이 SETNX 로 덮지 않는다
        InOrder order = inOrder(recoveryService, stockRepository);
        order.verify(recoveryService).recoverIfStockMissing();
        order.verify(stockRepository).addIssuedUsers(anyLong(), any());
    }

    @Test
    @DisplayName("재고 키가 없는 활성 쿠폰이 있으면 recoverIfStockMissing 이 recoverAll 로 quantity − DB 발급 수를 심는다")
    void recoverIfStockMissing_키가_없으면_recoverAll_로_심는다() {
        CouponStockRecoveryService real = new CouponStockRecoveryService(
                couponRepository, couponIssueRepository, stockRepository);
        given(stockRepository.getStock(1L)).willReturn(null);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(30L);
        given(stockRepository.initStockIfAbsent(anyLong(), anyLong())).willReturn(true);

        assertThat(real.recoverIfStockMissing()).isTrue();

        // 100 − 30 = 70. 대기 요청은 같은 Redis 의 스트림에 있어 재고 키와 함께 사라졌으므로 빼지 않는다
        verify(stockRepository).initStockIfAbsent(1L, 70L);
    }

    @Test
    @DisplayName("재고 키가 모두 살아 있으면 recoverIfStockMissing 은 아무것도 심지 않는다")
    void recoverIfStockMissing_키가_있으면_아무것도_하지_않는다() {
        CouponStockRecoveryService real = new CouponStockRecoveryService(
                couponRepository, couponIssueRepository, stockRepository);
        given(stockRepository.getStock(1L)).willReturn(40L);

        assertThat(real.recoverIfStockMissing()).isFalse();

        verify(stockRepository, never()).initStockIfAbsent(anyLong(), anyLong());
    }

    @Test
    @DisplayName("복구는 발급자 SET 을 먼저 복원하고 그 다음에 재고 키를 심는다")
    void recoverAll_발급자_SET_복원이_재고_키보다_앞선다() {
        CouponStockRecoveryService real = new CouponStockRecoveryService(
                couponRepository, couponIssueRepository, stockRepository);
        given(stockRepository.getStock(1L)).willReturn(null);
        given(couponIssueRepository.countByCoupon_Id(1L)).willReturn(30L);
        given(couponIssueRepository.findUserIdsByCouponId(1L)).willReturn(List.of(5L));
        given(stockRepository.initStockIfAbsent(anyLong(), anyLong())).willReturn(true);

        real.recoverAll();

        // 재고 키가 먼저 생기면 명단이 비어 있는 동안 이미 받은 사용자가 게이트를 다시 통과한다
        InOrder order = inOrder(stockRepository);
        order.verify(stockRepository).addIssuedUsers(1L, List.of(5L));
        order.verify(stockRepository).initStockIfAbsent(1L, 70L);
    }

    @Test
    @DisplayName("복구 중이면 접수는 판정하지 않고 503(UNAVAILABLE) 로 돌려보낸다")
    void enqueue_복구_중이면_접수가_보류된다() {
        given(recoveryService.isRecovering()).willReturn(true);
        CouponAsyncIssueService service = new CouponAsyncIssueService(
                couponRepository, mock(RedisTemplate.class), stockRepository, recoveryService,
                mock(DbLockIssueStrategy.class),
                new CouponRedisCircuit(CircuitBreakerRegistry.ofDefaults(), 50f, 10, 20, 5000L));

        assertThat(service.enqueue(1L, 100L).status()).isEqualTo(CouponIssueResult.Status.UNAVAILABLE);
        verify(stockRepository, never()).enqueue(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("헬스 체크는 복구를 프로브 스레드에서 돌리지 않고 한 번만 예약한다")
    void health_복구를_프로브_스레드에서_돌리지_않는다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> recoveryThread = new AtomicReference<>();
        // 복구가 끝나지 않는 상황 - 프로브 스레드에서 돌았다면 health() 가 여기서 막혀 돌아오지 못한다
        given(recoveryService.recoverIfStockMissing()).willAnswer(invocation -> {
            recoveryThread.set(Thread.currentThread());
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return true;
        });
        CouponIssueHealthIndicator indicator = new CouponIssueHealthIndicator(recoveryService);
        ReflectionTestUtils.setField(indicator, "checkIntervalMs", 0L);
        Thread caller = Thread.currentThread();

        long begin = System.nanoTime();
        indicator.health();
        indicator.health();
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(elapsedMs).as("health() 두 번이 복구를 기다리지 않는다").isLessThan(2_000L);
            assertThat(recoveryThread.get()).isNotSameAs(caller);
            // 복구가 도는 동안 다시 불려도 두 번째 복구를 예약하지 않는다
            verify(recoveryService, times(1)).recoverIfStockMissing();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("재고 키가 없다고 판정된 동안과 복구 중에는 OUT_OF_SERVICE 를 낸다")
    void health_키가_없거나_복구_중이면_OUT_OF_SERVICE_이다() {
        CouponIssueHealthIndicator indicator = new CouponIssueHealthIndicator(recoveryService);
        // 이 테스트는 보고되는 상태만 본다. 복구 예약이 돌면 전용 스레드가 같은 가짜 객체를 부르는 동안 이 스레드의 재스텁이 겹쳐 가끔 실패한다
        ReflectionTestUtils.setField(indicator, "checkIntervalMs", Long.MAX_VALUE);

        given(recoveryService.isStockMissing()).willReturn(true);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);

        given(recoveryService.isStockMissing()).willReturn(false);
        given(recoveryService.isRecovering()).willReturn(true);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);

        given(recoveryService.isRecovering()).willReturn(false);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("대조 서비스에 주기 실행 진입점이 있고 reconcileWithLock 을 부른다")
    void reconcile_주기_실행_진입점이_잠금_대조를_부른다() {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        given(redisson.getLock("coupon:reconcile:lock")).willReturn(lock);
        CouponStockReconciliationService service = new CouponStockReconciliationService(
                couponRepository, couponIssueRepository, stockRepository, redisson, new SimpleMeterRegistry());

        Method scheduled = Arrays.stream(CouponStockReconciliationService.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .findFirst().orElse(null);
        assertThat(scheduled).as("@Scheduled 진입점").isNotNull();
        ReflectionTestUtils.invokeMethod(service, scheduled.getName());

        verify(redisson).getLock("coupon:reconcile:lock");
    }

    @Test
    @DisplayName("쿠폰 종료(비활성화) 이벤트를 처리하면 closeCoupon 을 부른다")
    @SuppressWarnings("unchecked")
    void delayConsumer_종료_처리_뒤_closeCoupon_을_부른다() {
        groom.backend.domain.coupon.service.CouponService couponService =
                mock(groom.backend.domain.coupon.service.CouponService.class);
        given(couponService.disableCoupon(1L)).willReturn(true);
        CouponStockReconciliationService reconciliation = mock(CouponStockReconciliationService.class);
        ObjectProvider<CouponStockReconciliationService> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(reconciliation);
        CouponDelayConsumer consumer = new CouponDelayConsumer(couponService, provider);

        CouponDelayEvent event = new CouponDelayEvent();
        event.setCouponId(1L);
        consumer.consume(event);

        verify(reconciliation).closeCoupon(1L);
    }
}
