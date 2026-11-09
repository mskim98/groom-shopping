package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 발행 타임아웃은 "브로커가 기록했는지 모른다" 이다
 *
 * <p>브로커가 기록하고 응답만 유실됐다면 컨슈머가 그 요청을 INSERT 한다. 이때 재고를 되돌리면
 * 같은 자리가 한 번 더 팔려 1 건 초과 발급이 된다. 그래서 타임아웃은 되돌리지 않고
 * 같은 requestId 로 다시 보내며, 끝내 모르면 대조 대상으로 표시만 한다
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("쿠폰 발행 타임아웃")
class CouponPublishTimeoutTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private CouponIssueBatchWriter couponIssueBatchWriter;
    @Mock
    private CouponStockRedisRepository couponStockRedisRepository;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
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
        ReflectionTestUtils.setField(service, "publishResendAttempts", 2);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.SUCCESS);
    }

    @SuppressWarnings("unchecked")
    private void 모든_전송이_실패한다(Throwable error) {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(error);
        given(couponIssueKafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(failed);
    }

    @Test
    @DisplayName("타임아웃이면 재고를 되돌리지 않고 같은 requestId 로 다시 보낸 뒤 대조 표시를 남긴다")
    void enqueue_타임아웃은_반납하지_않고_재전송하고_대조표시를_남긴다() {
        모든_전송이_실패한다(new TimeoutException("Expiring 1 record(s) for coupon-issue-requests-0: 35000 ms has passed"));

        CouponIssueResult result = service.enqueue(1L, 100L);

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.ACCEPTED);
        // 첫 전송 + 재전송 2 회
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(couponIssueKafkaTemplate, timeout(3000).times(3))
                .send(eq("coupon-issue-requests"), eq("100"), payloads.capture());
        // 같은 requestId 가 실려야 컨슈머가 두 번째 도착분을 이미 처리한 요청으로 알아본다
        assertThat(payloads.getAllValues()).allSatisfy(p -> assertThat(p).contains(result.requestId()));
        assertThat(payloads.getAllValues()).containsOnly(payloads.getAllValues().get(0));
        // 끝내 모르면 대조 대상으로 표시한다
        verify(couponStockRedisRepository, timeout(3000))
                .markPublishUncertain(result.requestId(), 1L, 100L);
        // 재고를 되돌리면 브로커가 기록했던 경우 같은 자리가 두 번 팔린다
        verify(couponStockRedisRepository, never()).rollbackIssue(anyLong(), anyLong());
        verify(couponStockRedisRepository, never()).rollbackStockOnly(anyLong());
        // 상태를 실패로 덮지 않는다 - 컨슈머가 뒤늦게 확정하면 SUCCESS 로 바뀌어야 한다
        verify(valueOps, never()).set(anyString(), startsWith("FAILED"), any(Duration.class));
    }

    @Test
    @DisplayName("타임아웃이 아닌 전송 실패는 브로커에 없다는 뜻이라 자리를 되돌린다")
    void enqueue_확정_실패는_자리를_되돌린다() {
        모든_전송이_실패한다(new RecordTooLargeException("too large"));

        CouponIssueResult result = service.enqueue(1L, 100L);

        verify(couponStockRedisRepository, timeout(3000).times(1)).rollbackIssue(1L, 100L);
        verify(couponIssueKafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        verify(couponStockRedisRepository, never()).markPublishUncertain(anyString(), anyLong(), anyLong());
        verify(valueOps, timeout(3000)).set(eq("coupon:issue:status:" + result.requestId()),
                eq("FAILED:SERVER_ERROR"), any(Duration.class));
    }
}
