package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Redis 가 끊기면 접수 진입점이 DB 행 잠금 경로로 동기 발급한다
 *
 * <p>접수 스크립트가 예외를 던지거나 {@code redis-coupon} 회로가 열려 있으면 502 · 503 으로 돌려보내지 않고
 * 쿠폰 행 잠금 · DB 발급 수 대비 수량 확인 · INSERT 로 그 자리에서 답한다.
 * Redis 저장소를 던지는 스파이로 바꿔 장애를 만든다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 Redis 장애 DB 경로")
class CouponRedisDownFallbackTest {

    @Autowired
    private CouponAsyncIssueService service;
    @MockitoSpyBean
    private CouponStockRedisRepository stockRepository;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;

    private CouponStreamFixture fixture;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
        breaker = circuitBreakerRegistry.circuitBreaker("redis-coupon");
        breaker.reset();
    }

    @AfterEach
    void tearDown() {
        breaker.reset();
        reset(stockRepository);
        fixture.cleanup();
    }

    private void redisThrows() {
        doThrow(new RedisConnectionFailureException("Redis 연결 끊김"))
                .when(stockRepository).enqueue(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("접수 스크립트가 던지면 DB 경로로 발급하고 coupon_issue 행과 성공을 돌려준다")
    void enqueue_스크립트가_던지면_db경로로_발급한다() {
        Long couponId = fixture.coupon(3);
        Long userId = fixture.user();
        redisThrows();

        CouponIssueResult result = service.enqueue(couponId, userId);

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        assertThat(result.couponIssueId()).isNotNull();
        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        // 발급 수량 상한은 DB 가 센다 - 쿠폰 행은 불변 발행 수량이다
        assertThat(couponRepository.findById(couponId).orElseThrow().getQuantity()).isEqualTo(3L);
    }

    @Test
    @DisplayName("DB 경로에서도 수량이 다 찼으면 품절, 같은 사용자는 중복이다")
    void enqueue_db경로도_품절과_중복을_구분한다() {
        Long couponId = fixture.coupon(1);
        Long first = fixture.user();
        Long second = fixture.user();
        redisThrows();

        assertThat(service.enqueue(couponId, first).status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        assertThat(service.enqueue(couponId, first).status()).isEqualTo(CouponIssueResult.Status.ALREADY_ISSUED);
        assertThat(service.enqueue(couponId, second).status()).isEqualTo(CouponIssueResult.Status.SOLD_OUT);

        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("회로가 열려 있으면 Redis 를 부르지 않고 바로 DB 경로로 발급한다")
    void enqueue_회로가_열리면_redis를_부르지_않는다() {
        Long couponId = fixture.coupon(3);
        Long userId = fixture.user();
        stockRepository.initStock(couponId, 3L);
        breaker.transitionToOpenState();

        CouponIssueResult result = service.enqueue(couponId, userId);

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.SUCCESS);
        assertThat(couponIssueRepository.countByCoupon_IdAndUserId(couponId, userId)).isEqualTo(1L);
        verify(stockRepository, never()).enqueue(anyLong(), anyLong(), any());
        // Redis 재고와 명단은 건드리지 않았다
        assertThat(stockRepository.getStock(couponId)).isEqualTo(3L);
    }
}
