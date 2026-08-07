package groom.backend.infrastructure.kafka;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private SetOperations<String, String> setOps;
    @InjectMocks
    private PaymentEventConsumer consumer;

    private ConsumerRecord<String, String> recordWithOutboxId(String outboxId) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("payment-events", 0, 0L, "key", "{}");
        record.headers().add("outbox-id", outboxId.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    void consume_처음_처리하는_outboxId면_dedup_Set에_추가한다() {
        given(redisTemplate.opsForSet()).willReturn(setOps);
        given(setOps.add(eq("payment-event:processed"), eq("ob-1"))).willReturn(1L); // 신규

        consumer.consume(recordWithOutboxId("ob-1"));

        verify(setOps, times(1)).add("payment-event:processed", "ob-1");
    }

    @Test
    void consume_이미_처리한_outboxId면_중복으로_무시한다() {
        given(redisTemplate.opsForSet()).willReturn(setOps);
        given(setOps.add(eq("payment-event:processed"), eq("ob-1"))).willReturn(0L); // 중복

        // 중복이면 예외 없이 조용히 무시(early return)
        consumer.consume(recordWithOutboxId("ob-1"));

        verify(setOps, times(1)).add("payment-event:processed", "ob-1");
    }
}
