package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.repository.UserRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class CouponAsyncIssueServiceTest {

    @Mock
    private CouponIssueService couponIssueService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private CouponStockRedisRepository couponStockRedisRepository;

    private CouponAsyncIssueService service;

    @BeforeEach
    void setUp() {
        service = new CouponAsyncIssueService(
                couponIssueService, userRepository, redisTemplate,
                kafkaTemplate, new ObjectMapper(), couponStockRedisRepository);
    }

    @Test
    @DisplayName("접수 단계에서 게이트를 통과한 요청만 브로커로 간다")
    void enqueue_게이트를_통과하면_토픽에_발행한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.SUCCESS);

        String requestId = service.enqueue(1L, 100L);

        assertThat(requestId).isNotBlank();
        verify(kafkaTemplate, times(1)).send(eq("coupon-issue-requests"), eq("1"), anyString());
        verify(valueOps, times(1)).set(anyString(), eq("WAITING"), any(Duration.class));
    }

    @Test
    @DisplayName("품절이면 브로커에 싣지 않고 즉시 거절한다")
    void enqueue_게이트가_품절이면_발행하지_않는다() {
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.OUT_OF_STOCK);

        assertThatThrownBy(() -> service.enqueue(1L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_OUT_OF_STOCK);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("중복 요청도 브로커에 싣지 않는다")
    void enqueue_게이트가_중복이면_발행하지_않는다() {
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.ALREADY_ISSUED);

        assertThatThrownBy(() -> service.enqueue(1L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("재고 미초기화는 게이트를 통과시키고 판정을 컨슈머에 넘긴다")
    void enqueue_재고가_미초기화면_예약없이_발행한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.NOT_INITIALIZED);

        service.enqueue(1L, 100L);

        verify(kafkaTemplate, times(1)).send(eq("coupon-issue-requests"), eq("1"), anyString());
    }

    @Test
    @DisplayName("게이트가 이미 예약한 요청은 컨슈머가 재고를 다시 깎지 않는다")
    void process_게이트예약분은_confirmIssue로_확정한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(userRepository.findById(100L)).willReturn(Optional.of(mock(User.class)));

        service.process(new CouponIssueRequestEvent("req-1", 1L, 100L, true));

        verify(couponIssueService, times(1)).confirmIssue(eq(1L), any(User.class));
        verify(couponIssueService, never()).issueCouponInDbOnly(anyLong(), any(User.class));
        verify(valueOps, times(1)).set(eq("coupon:issue:status:req-1"), eq("SUCCESS"), any(Duration.class));
    }

    @Test
    @DisplayName("게이트 예약이 없는 요청은 재시도해도 미초기화면 DB 폴백으로 확정한다")
    void process_예약없는_요청은_재시도후_미초기화면_DB폴백으로_확정한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(userRepository.findById(100L)).willReturn(Optional.of(mock(User.class)));
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.NOT_INITIALIZED);

        service.process(new CouponIssueRequestEvent("req-2", 1L, 100L, false));

        verify(couponIssueService, times(1)).issueCouponInDbOnly(eq(1L), any(User.class));
        verify(couponIssueService, never()).confirmIssue(anyLong(), any(User.class));
    }

    @Test
    @DisplayName("접수 이후 재고가 심어졌으면 컨슈머의 재시도가 예약하고 DB 폴백을 타지 않는다")
    void process_예약없는_요청도_재시도가_성공하면_confirmIssue로_확정한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(userRepository.findById(100L)).willReturn(Optional.of(mock(User.class)));
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.SUCCESS);

        service.process(new CouponIssueRequestEvent("req-3", 1L, 100L, false));

        // 재시도가 재고를 깎았으므로 롤백을 가진 confirmIssue 로 가야 한다
        verify(couponIssueService, times(1)).confirmIssue(eq(1L), any(User.class));
        // DB 비관적 락 폴백까지 타면 같은 요청이 재고를 두 번 깎는다
        verify(couponIssueService, never()).issueCouponInDbOnly(anyLong(), any(User.class));
        verify(valueOps, times(1)).set(eq("coupon:issue:status:req-3"), eq("SUCCESS"), any(Duration.class));
    }

    @Test
    @DisplayName("처리 실패는 사유를 남긴다")
    void process_발급_실패시_상태를_FAILED로_저장한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(userRepository.findById(100L)).willReturn(Optional.of(mock(User.class)));
        given(couponIssueService.confirmIssue(eq(1L), any(User.class)))
                .willThrow(new BusinessException(ErrorCode.COUPON_STORE_MISMATCH));

        service.process(new CouponIssueRequestEvent("req-1", 1L, 100L, true));

        verify(valueOps, times(1))
                .set(eq("coupon:issue:status:req-1"), eq("FAILED:COUPON_STORE_MISMATCH"), any(Duration.class));
    }

    @Test
    @DisplayName("상태가 없으면 UNKNOWN 이다")
    void getStatus_상태가_없으면_UNKNOWN을_반환한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get("coupon:issue:status:req-x")).willReturn(null);

        assertThat(service.getStatus("req-x")).isEqualTo("UNKNOWN");
    }
}
