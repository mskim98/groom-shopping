package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.application.coupon.CouponIssueBatchWriter;
import groom.backend.application.coupon.CouponStockRecoveryService;
import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

/**
 * 읽을 수 없는 메시지는 건너뛰지 않고 DLQ 로 보낸다
 *
 * <p>로그만 남기고 건너뛰면 그 메시지가 잡아 둔 Redis 재고와 발급자 SET 항목이 영원히 풀리지 않고,
 * 원문도 로그 보관 기간이 지나면 사라진다. DLQ 에는 원문을 그대로 실어 사람이 다시 읽을 수 있게 한다
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("쿠폰 컨슈머 읽을 수 없는 메시지")
class CouponConsumerPoisonMessageTest {

    private static final String GOOD =
            "{\"requestId\":\"r-1\",\"couponId\":1,\"userId\":100,\"gateReserved\":true}";
    private static final String BROKEN_JSON = "not-json{";
    // 필드 값의 형식이 틀려 읽기는 실패하지만 couponId · userId 는 읽힌다
    private static final String BAD_TYPE =
            "{\"requestId\":\"r-9\",\"couponId\":1,\"userId\":100,\"gateReserved\":\"maybe\"}";

    @Mock
    private CouponAsyncIssueService asyncIssueService;
    @Mock
    private CouponIssueDlqProducer dlqProducer;

    private ConsumerRecord<String, String> record(long offset, String key, String value) {
        return new ConsumerRecord<>(CouponAsyncIssueService.REQUEST_TOPIC, 0, offset, key, value);
    }

    @Test
    @DisplayName("읽을 수 없는 레코드는 원문 그대로 DLQ 로 가고 읽히는 레코드만 확정으로 간다")
    void consume_읽을_수_없는_레코드는_DLQ_로_간다() {
        CouponIssueRequestConsumer consumer =
                new CouponIssueRequestConsumer(asyncIssueService, new ObjectMapper(), dlqProducer);

        consumer.consume(List.of(record(0, "100", GOOD), record(1, "55", BROKEN_JSON)));

        verify(dlqProducer).send(eq("55"), eq(BROKEN_JSON), any(Throwable.class));
        ArgumentCaptor<List<CouponIssueRequestEvent>> processed = ArgumentCaptor.forClass(List.class);
        verify(asyncIssueService).processBatch(processed.capture());
        assertThat(processed.getValue()).extracting(CouponIssueRequestEvent::requestId).containsExactly("r-1");
    }

    @Test
    @DisplayName("읽을 수 없는 레코드의 Redis 예약 반납을 시도한다")
    void consume_읽을_수_없는_레코드의_예약_반납을_시도한다() {
        CouponIssueRequestConsumer consumer =
                new CouponIssueRequestConsumer(asyncIssueService, new ObjectMapper(), dlqProducer);

        consumer.consume(List.of(record(0, "100", BAD_TYPE)));

        verify(asyncIssueService).abandonUnreadable(eq(BAD_TYPE), any(Throwable.class));
    }

    @Test
    @DisplayName("DLQ 전송이 실패하면 건너뛰지 않고 예외를 던져 배치를 다시 받는다")
    void consume_DLQ_전송_실패는_예외로_올린다() {
        doThrow(new IllegalStateException("dlq down")).when(dlqProducer).send(any(), any(), any());
        CouponIssueRequestConsumer consumer =
                new CouponIssueRequestConsumer(asyncIssueService, new ObjectMapper(), dlqProducer);

        assertThatThrownBy(() -> consumer.consume(List.of(record(0, "55", BROKEN_JSON))))
                .isInstanceOf(IllegalStateException.class);
        // 원문을 어디에도 남기지 못했으면 예약도 풀지 않는다 - 다시 받을 때 처리된다
        verify(asyncIssueService, never()).processBatch(anyList());
    }

    @Test
    @DisplayName("abandonUnreadable 은 couponId · userId 가 읽히면 예약을 반납하고 안 읽히면 건드리지 않는다")
    @SuppressWarnings("unchecked")
    void abandonUnreadable_읽히는_예약만_반납한다() {
        CouponStockRedisRepository stock = mock(CouponStockRedisRepository.class);
        CouponIssueRepository issueRepository = mock(CouponIssueRepository.class);
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        given(redisTemplate.opsForValue()).willReturn(mock(ValueOperations.class));
        CouponAsyncIssueService service = new CouponAsyncIssueService(
                mock(CouponRepository.class), issueRepository, mock(CouponIssueBatchWriter.class), stock,
                redisTemplate, mock(KafkaTemplate.class), new ObjectMapper(),
                mock(RedissonClient.class), mock(CouponStockRecoveryService.class));

        boolean readable = service.abandonUnreadable(BAD_TYPE, new IllegalArgumentException("bad type"));
        boolean garbage = service.abandonUnreadable(BROKEN_JSON, new IllegalArgumentException("bad json"));

        assertThat(readable).isTrue();
        assertThat(garbage).isFalse();
        verify(stock).rollbackIssue(1L, 100L);
        // 읽히지 않은 쪽(BROKEN_JSON)은 되돌릴 대상을 몰라 호출이 한 번뿐이다
        verify(stock, times(1)).rollbackIssue(anyLong(), anyLong());
    }
}
