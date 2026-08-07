package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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

    private CouponAsyncIssueService service;

    @BeforeEach
    void setUp() {
        service = new CouponAsyncIssueService(
                couponIssueService, userRepository, redisTemplate,
                kafkaTemplate, new ObjectMapper());
    }

    @Test
    void enqueue_요청을_큐에_적재하고_requestId를_반환한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);

        String requestId = service.enqueue(1L, 100L);

        assertThat(requestId).isNotBlank();
        // 요청 토픽 발행
        verify(kafkaTemplate, times(1)).send(eq("coupon-issue-requests"), eq("1"), anyString());
        // 초기 상태 WAITING 기록
        verify(valueOps, times(1)).set(anyString(), eq("WAITING"), any(Duration.class));
    }

    @Test
    void process_발급_성공시_상태를_SUCCESS로_저장한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(userRepository.findById(100L)).willReturn(Optional.of(mock(User.class)));

        service.process(new CouponIssueRequestEvent("req-1", 1L, 100L));

        // 비동기 경로도 동기와 같은 Lua 발급 코어를 탄다 (DB 전용 경로가 아니다)
        verify(couponIssueService, times(1)).issueCouponWithoutLock(eq(1L), any(User.class));
        verify(valueOps, times(1)).set(eq("coupon:issue:status:req-1"), eq("SUCCESS"), any(Duration.class));
    }

    @Test
    void process_발급_실패시_상태를_FAILED로_저장한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(userRepository.findById(100L)).willReturn(Optional.of(mock(User.class)));
        given(couponIssueService.issueCouponWithoutLock(eq(1L), any(User.class)))
                .willThrow(new BusinessException(ErrorCode.COUPON_OUT_OF_STOCK));

        service.process(new CouponIssueRequestEvent("req-1", 1L, 100L));

        verify(valueOps, times(1))
                .set(eq("coupon:issue:status:req-1"), eq("FAILED:COUPON_OUT_OF_STOCK"), any(Duration.class));
    }

    @Test
    void getStatus_상태가_없으면_UNKNOWN을_반환한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get("coupon:issue:status:req-x")).willReturn(null);

        assertThat(service.getStatus("req-x")).isEqualTo("UNKNOWN");
    }

}
