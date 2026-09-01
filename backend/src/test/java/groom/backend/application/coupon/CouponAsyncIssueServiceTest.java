package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import groom.backend.application.coupon.strategy.CouponIssueResult;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * S2 접수·확정의 계약을 고정한다
 *
 * <p>가장 중요한 단언은 <b>확정 경로가 {@code coupon} 테이블을 만지지 않는다</b>는 것이다
 * 그 문장이 되살아나면 같은 쿠폰의 모든 확정이 한 행을 두고 줄을 서므로,
 * 컨슈머를 몇 개로 늘려도 그 행이 상한을 정한다 - 이 라운드의 본체가 사라진다
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponAsyncIssueServiceTest {

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
    @Mock
    private RedisOperations<String, String> pipelinedOps;

    private CouponAsyncIssueService service;

    @BeforeEach
    void setUp() {
        service = new CouponAsyncIssueService(
                couponRepository, couponIssueRepository, couponIssueBatchWriter,
                couponStockRedisRepository, redisTemplate, couponIssueKafkaTemplate,
                new ObjectMapper(), redissonClient, couponStockRecoveryService);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(couponIssueKafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    }

    private Coupon 유효한_쿠폰() {
        return Coupon.builder().id(1L).name("쿠폰").quantity(100L).amount(1000)
                .type(CouponType.DISCOUNT).isActive(true)
                .expireDate(LocalDate.now().plusDays(30)).build();
    }

    /**
     * 상태 기록은 파이프라인 안에서 일어난다. 콜백을 직접 실행해야 무엇을 썼는지 볼 수 있다
     */
    private void 파이프라인_실행() {
        ArgumentCaptor<SessionCallback<?>> captor = ArgumentCaptor.forClass(SessionCallback.class);
        verify(redisTemplate).executePipelined(captor.capture());
        given(pipelinedOps.opsForValue()).willReturn(valueOps);
        captor.getValue().execute(pipelinedOps);
    }

    @Test
    @DisplayName("게이트를 통과한 요청만 브로커로 가고, 키는 userId 다")
    void enqueue_통과분만_userId_키로_발행한다() {
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.SUCCESS);

        CouponIssueResult result = service.enqueue(1L, 100L);

        assertThat(result.status()).isEqualTo(CouponIssueResult.Status.ACCEPTED);
        assertThat(result.requestId()).isNotBlank();
        // 키가 couponId 이면 한 쿠폰의 이벤트가 파티션 하나에만 쌓여 컨슈머를 늘려도 소용이 없다
        verify(couponIssueKafkaTemplate).send(eq("coupon-issue-requests"), eq("100"), anyString());
        verify(valueOps).set(anyString(), eq("WAITING"), any(Duration.class));
    }

    @Test
    @DisplayName("품절이면 브로커에 싣지 않는다")
    void enqueue_품절이면_발행하지_않는다() {
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.OUT_OF_STOCK);

        assertThat(service.enqueue(1L, 100L).status()).isEqualTo(CouponIssueResult.Status.SOLD_OUT);
        verify(couponIssueKafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("중복이면 브로커에 싣지 않는다")
    void enqueue_중복이면_발행하지_않는다() {
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.ALREADY_ISSUED);

        assertThat(service.enqueue(1L, 100L).status()).isEqualTo(CouponIssueResult.Status.ALREADY_ISSUED);
        verify(couponIssueKafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("재고 키가 없으면 접수는 기다리지 않고 즉시 돌려보낸다")
    void enqueue_미초기화면_싣지_않고_보류로_응답한다() {
        // 쿠폰은 실재한다. 없는 쿠폰이면 503 이 아니라 404 로 끊어야 하므로 이 분기에서만 한 번 확인한다
        given(couponRepository.findById(1L)).willReturn(Optional.of(유효한_쿠폰()));
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.NOT_INITIALIZED);

        // 판정할 근거가 없는 요청을 통과시키면 그 뒤에 판정할 사람이 아무도 없다
        // 여기서 심어질 때까지 기다리면 그 대기가 그대로 요청 스레드 점유가 된다
        assertThat(service.enqueue(1L, 100L).status()).isEqualTo(CouponIssueResult.Status.UNAVAILABLE);
        verify(couponIssueKafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("확정은 배치 한 문장으로 넣고 쿠폰 수량을 건드리지 않는다")
    void processBatch_배치로_넣고_수량은_건드리지_않는다() {
        given(couponRepository.findById(1L)).willReturn(Optional.of(유효한_쿠폰()));
        given(couponIssueBatchWriter.insertAll(eq(1L), any(), anyList())).willReturn(Set.of(100L, 200L));

        service.processBatch(List.of(
                new CouponIssueRequestEvent("req-1", 1L, 100L, true),
                new CouponIssueRequestEvent("req-2", 1L, 200L, true)));

        // 두 건이 두 문장이 아니라 한 문장으로 간다. 배치가 줄이는 것은 커넥션·트랜잭션·왕복이다
        verify(couponIssueBatchWriter, times(1)).insertAll(eq(1L), any(), anyList());
        // UPDATE coupon SET quantity = quantity - 1 이 되살아나면 이 단언이 깨진다
        verify(couponRepository, never()).decreaseQuantityAtomically(anyLong());

        파이프라인_실행();
        verify(valueOps).set(eq("coupon:issue:status:req-1"), eq("SUCCESS"), any(Duration.class));
        verify(valueOps).set(eq("coupon:issue:status:req-2"), eq("SUCCESS"), any(Duration.class));
    }

    @Test
    @DisplayName("확정은 재고를 다시 검사하지 않는다")
    void processBatch_게이트예약분은_재판정하지_않는다() {
        given(couponRepository.findById(1L)).willReturn(Optional.of(유효한_쿠폰()));
        given(couponIssueBatchWriter.insertAll(eq(1L), any(), anyList())).willReturn(Set.of(100L));

        service.processBatch(List.of(new CouponIssueRequestEvent("req-1", 1L, 100L, true)));

        // 여기서 세면 세는 것과 넣는 것 사이에 남이 끼어들고, 막으려면 방금 걷어낸 행 잠금이 돌아온다
        verify(couponStockRedisRepository, never()).tryIssue(anyLong(), anyLong());
    }

    @Test
    @DisplayName("들어가지 않은 건은 자리만 되돌리고 발급자 명단에는 남긴다")
    void processBatch_중복은_재고만_되돌린다() {
        given(couponRepository.findById(1L)).willReturn(Optional.of(유효한_쿠폰()));
        given(couponIssueBatchWriter.insertAll(eq(1L), any(), anyList())).willReturn(Set.of());

        service.processBatch(List.of(new CouponIssueRequestEvent("req-1", 1L, 100L, true)));

        // 명단에서 빼면 게이트가 그를 다시 통과시켜 같은 실패를 반복한다
        verify(couponStockRedisRepository).rollbackStockOnly(1L);
        verify(couponStockRedisRepository, never()).rollbackIssue(anyLong(), anyLong());

        파이프라인_실행();
        verify(valueOps).set(eq("coupon:issue:status:req-1"),
                eq("FAILED:COUPON_ALREADY_ISSUED"), any(Duration.class));
    }

    @Test
    @DisplayName("재처리를 다 쓰면 배정한 자리를 되돌린다")
    void abandonBatch_자리를_반납하고_실패로_남긴다() {
        service.abandonBatch(
                List.of(new CouponIssueRequestEvent("req-1", 1L, 100L, true)),
                new IllegalStateException("db down"));

        // 되돌리지 않으면 그 수량은 아무도 받지 못한 채 사라진다
        // 주기 배치를 없앴으므로 이 자리가 마지막 보상 지점이다
        verify(couponStockRedisRepository).rollbackIssue(1L, 100L);

        파이프라인_실행();
        verify(valueOps).set(eq("coupon:issue:status:req-1"),
                eq("FAILED:SERVER_ERROR"), any(Duration.class));
    }

    @Test
    @DisplayName("없는 쿠폰은 보류가 아니라 404 로 끊는다")
    void enqueue_없는_쿠폰이면_보류로_돌려보내지_않는다() {
        given(couponRepository.findById(1L)).willReturn(Optional.empty());
        given(couponStockRedisRepository.tryIssue(1L, 100L))
                .willReturn(CouponStockRedisRepository.IssueResult.NOT_INITIALIZED);

        // 503 을 주면 클라이언트가 영원히 재시도한다
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.enqueue(1L, 100L))
                .isInstanceOf(groom.backend.common.exception.BusinessException.class);
        verify(couponIssueKafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("상태가 없으면 UNKNOWN 이다")
    void getStatus_상태가_없으면_UNKNOWN을_반환한다() {
        given(valueOps.get("coupon:issue:status:req-x")).willReturn(null);

        assertThat(service.getStatus("req-x")).isEqualTo("UNKNOWN");
    }
}
