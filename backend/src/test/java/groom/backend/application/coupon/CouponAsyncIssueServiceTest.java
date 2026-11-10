package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.application.coupon.strategy.DbLockIssueStrategy;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 접수 진입점의 판정 매핑을 고정한다 (Redis · DB 는 가짜)
 *
 * <p>접수는 Lua 한 번의 결과를 응답으로 옮기기만 한다. 저장은 스트림 컨슈머가 하므로 여기서는
 * 쿠폰 테이블도 발급 테이블도 만지지 않고, Kafka 도 없다
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponAsyncIssueServiceTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponStockRedisRepository couponStockRedisRepository;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private CouponStockRecoveryService couponStockRecoveryService;
    @Mock
    private DbLockIssueStrategy dbLockIssueStrategy;

    private CouponAsyncIssueService service;

    @BeforeEach
    void setUp() {
        service = new CouponAsyncIssueService(couponRepository, redisTemplate, couponStockRedisRepository,
                couponStockRecoveryService, dbLockIssueStrategy,
                new CouponRedisCircuit(CircuitBreakerRegistry.ofDefaults(), 50f, 10, 20, 5000L));
        given(redisTemplate.opsForValue()).willReturn(valueOps);
    }

    private Coupon 유효한_쿠폰() {
        return Coupon.builder().id(1L).name("쿠폰").quantity(100L).amount(1000)
                .type(CouponType.DISCOUNT).isActive(true)
                .expireDate(LocalDate.now().plusDays(30)).build();
    }

    @Test
    @DisplayName("게이트를 통과하면 접수 번호를 돌려주고 그 번호로 스트림에 싣는다")
    void enqueue_통과하면_접수번호로_싣는다() {
        given(couponStockRedisRepository.enqueue(eq(1L), eq(100L), anyString()))
                .willReturn(CouponStockRedisRepository.IssueResult.SUCCESS);

        CouponIssueResult result = service.enqueue(1L, 100L);

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.ACCEPTED);
        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        verify(couponStockRedisRepository).enqueue(eq(1L), eq(100L), requestId.capture());
        assertThat(result.requestId()).isEqualTo(requestId.getValue());
        // 접수는 쿠폰 · 발급 테이블을 만지지 않는다
        verify(dbLockIssueStrategy, never()).issueFor(anyLong(), anyLong());
    }

    @Test
    @DisplayName("품절이면 품절로 답한다")
    void enqueue_품절이면_품절이다() {
        given(couponStockRedisRepository.enqueue(eq(1L), eq(100L), anyString()))
                .willReturn(CouponStockRedisRepository.IssueResult.OUT_OF_STOCK);

        assertThat(service.enqueue(1L, 100L).status()).isEqualTo(CouponIssueResult.Status.SOLD_OUT);
    }

    @Test
    @DisplayName("중복이면 중복으로 답한다")
    void enqueue_중복이면_중복이다() {
        given(couponStockRedisRepository.enqueue(eq(1L), eq(100L), anyString()))
                .willReturn(CouponStockRedisRepository.IssueResult.ALREADY_ISSUED);

        assertThat(service.enqueue(1L, 100L).status()).isEqualTo(CouponIssueResult.Status.ALREADY_ISSUED);
    }

    @Test
    @DisplayName("재고 키가 없으면 접수는 기다리지 않고 즉시 보류로 돌려보낸다")
    void enqueue_미초기화면_보류로_응답한다() {
        // 쿠폰은 실재한다. 없는 쿠폰이면 503 이 아니라 404 로 끊어야 하므로 이 분기에서만 한 번 확인한다
        given(couponRepository.findById(1L)).willReturn(Optional.of(유효한_쿠폰()));
        given(couponStockRedisRepository.enqueue(eq(1L), eq(100L), anyString()))
                .willReturn(CouponStockRedisRepository.IssueResult.NOT_INITIALIZED);

        assertThat(service.enqueue(1L, 100L).status()).isEqualTo(CouponIssueResult.Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("없는 쿠폰은 보류가 아니라 404 로 끊는다")
    void enqueue_없는_쿠폰이면_404다() {
        given(couponRepository.findById(1L)).willReturn(Optional.empty());
        given(couponStockRedisRepository.enqueue(eq(1L), eq(100L), anyString()))
                .willReturn(CouponStockRedisRepository.IssueResult.NOT_INITIALIZED);

        // 503 을 주면 클라이언트가 영원히 재시도한다
        assertThatThrownBy(() -> service.enqueue(1L, 100L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COUPON_NOT_FOUND));
    }

    @Test
    @DisplayName("복구 중이면 판정하지 않고 보류로 돌려보낸다")
    void enqueue_복구중이면_보류다() {
        given(couponStockRecoveryService.isRecovering()).willReturn(true);

        assertThat(service.enqueue(1L, 100L).status()).isEqualTo(CouponIssueResult.Status.UNAVAILABLE);
        verify(couponStockRedisRepository, never()).enqueue(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("Redis 장애로 DB 경로가 발급하면 그 사용자의 내 쿠폰 목록 캐시를 비운다")
    void enqueue_db경로_성공이면_목록_캐시를_비운다() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        given(cacheManager.getCache("user-coupons-list-cache")).willReturn(cache);
        ReflectionTestUtils.setField(service, "couponCacheManager", cacheManager);
        given(couponStockRedisRepository.enqueue(eq(1L), eq(100L), anyString()))
                .willThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        given(dbLockIssueStrategy.issueFor(1L, 100L)).willReturn(CouponIssueResult.success(55L));

        CouponIssueResult result = service.enqueue(1L, 100L);

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        // 비우지 않으면 방금 받은 쿠폰이 목록에 5분간 보이지 않는다
        verify(cache).evict(100L);
    }

    @Test
    @DisplayName("상태가 없으면 UNKNOWN 이다")
    void getStatus_상태가_없으면_UNKNOWN을_반환한다() {
        given(valueOps.get("coupon:issue:status:req-x")).willReturn(null);

        assertThat(service.getStatus("req-x")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("상태 키 값을 그대로 돌려준다")
    void getStatus_값이_있으면_그대로_돌려준다() {
        given(valueOps.get("coupon:issue:status:req-y")).willReturn("WAITING");

        assertThat(service.getStatus("req-y")).isEqualTo("WAITING");
    }
}
