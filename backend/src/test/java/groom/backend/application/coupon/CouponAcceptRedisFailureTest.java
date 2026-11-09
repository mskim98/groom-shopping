package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.common.exception.GlobalExceptionHandler;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 접수 경로의 Redis 장애는 500 이 아니라 503 이다
 *
 * <p>500 은 "서버가 고장났다" 로 읽혀 클라이언트가 재시도하지 않고, 503 은 잠시 뒤 다시 시도하라는 뜻이다
 * 재고 판정을 Redis 한 곳에서 하므로 Redis 가 끊기면 판정 자체가 불가능하다 - 통과시킬 수도 없다
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("쿠폰 접수 Redis 장애")
class CouponAcceptRedisFailureTest {

    @Mock
    private groom.backend.domain.coupon.repository.CouponRepository couponRepository;
    @Mock
    private groom.backend.domain.coupon.repository.CouponIssueRepository couponIssueRepository;
    @Mock
    private CouponIssueBatchWriter couponIssueBatchWriter;
    @Mock
    private CouponStockRedisRepository couponStockRedisRepository;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private KafkaTemplate<String, String> couponIssueKafkaTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private CouponStockRecoveryService couponStockRecoveryService;

    private CouponAsyncIssueService service;

    @BeforeEach
    void setUp() {
        service = new CouponAsyncIssueService(
                couponRepository, couponIssueRepository, couponIssueBatchWriter,
                couponStockRedisRepository, redisTemplate, couponIssueKafkaTemplate,
                new ObjectMapper(), redissonClient, couponStockRecoveryService);
    }

    static Stream<Throwable> redisFailures() {
        return Stream.of(
                new RedisConnectionFailureException("Unable to connect to Redis"),
                new QueryTimeoutException("Redis command timed out"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("redisFailures")
    @DisplayName("게이트의 Redis 오류는 COUPON_ISSUE_UNAVAILABLE(503) 으로 바뀐다")
    void enqueue_Redis오류는_503으로_바뀐다(Throwable failure) {
        given(couponStockRedisRepository.tryIssue(1L, 100L)).willThrow((RuntimeException) failure);

        assertThatThrownBy(() -> service.enqueue(1L, 100L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COUPON_ISSUE_UNAVAILABLE);
                    assertThat(e.getErrorCode().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    // 전역 핸들러를 거치면 실제 응답 코드가 503 이다
                    assertThat(new GlobalExceptionHandler().handleBusinessException(e).getStatusCode())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        // 판정하지 못한 요청은 브로커에 싣지 않는다
        verify(couponIssueKafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
