package groom.backend.infrastructure.resilience;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;

/**
 * {@link DbLedgerOpenException} 은 재시도 횟수에도 DLQ 에도 넣지 않는다 - 읽은 레코드를 처음 오프셋으로 되감고 컨테이너를 멈춰
 * 회로가 풀린 뒤 같은 레코드를 다시 읽게 한다. 다른 예외는 기존 재시도 · DLQ 경로 그대로다
 */
public class DbLedgerPauseErrorHandler extends DefaultErrorHandler {

    public DbLedgerPauseErrorHandler(org.springframework.kafka.listener.ConsumerRecordRecoverer recoverer, BackOff backOff) {
        super(recoverer, backOff);
    }

    public DbLedgerPauseErrorHandler(BackOff backOff) {
        super(backOff);
    }

    @Override
    public void handleRemaining(Exception thrownException, List<ConsumerRecord<?, ?>> records,
                                Consumer<?, ?> consumer, MessageListenerContainer container) {
        if (!hasOpenCause(thrownException)) {
            super.handleRemaining(thrownException, records, consumer, container);
            return;
        }
        Map<TopicPartition, Long> firstOffsets = new LinkedHashMap<>();
        for (ConsumerRecord<?, ?> record : records) {
            firstOffsets.putIfAbsent(new TopicPartition(record.topic(), record.partition()), record.offset());
        }
        firstOffsets.forEach(consumer::seek);
        // 다음 poll 이 같은 레코드를 바로 다시 주지 않게 즉시 멈춘다 - 재개는 DbLedgerCircuit.checkOnce 가 한다
        container.pause();
    }

    private static boolean hasOpenCause(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause() == cur ? null : cur.getCause()) {
            if (cur instanceof DbLedgerOpenException) {
                return true;
            }
        }
        return false;
    }
}
