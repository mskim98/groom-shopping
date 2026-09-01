package groom.backend.infrastructure.kafka;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * 컨슈머 랙을 읽는다 - 발행됐지만 아직 소비되지 않은 건수
 *
 * <p>이 값이 필요한 이유는 Redis 가 사라졌을 때 복원값을 계산해야 해서다
 * {@code COUNT(coupon_issue)} 는 확정된 것만 세므로, 게이트는 통과했지만 아직 저장되지 않은 요청이 빠진다
 * 그만큼 재고를 많게 계산하고, 많게 계산한 재고는 그대로 초과 발급이 된다
 *
 * <p>랙은 토픽·그룹 단위라 쿠폰별로 나눌 수단이 없다. 동시에 여러 쿠폰을 여는 경우에는
 * 랙 전체를 빼서 보수적으로 계산한다 - 미달 방향이라 안전하고 부족분은 종료 시 정산이 메운다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaLagReader {

    private static final long ADMIN_TIMEOUT_SECONDS = 5;

    private final KafkaAdmin kafkaAdmin;

    /**
     * @return 해당 토픽·그룹의 랙 합계. 읽지 못하면 {@code -1}
     */
    public long lagOf(String topic, String groupId) {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            committed.keySet().stream()
                    .filter(tp -> tp.topic().equals(topic))
                    .forEach(tp -> request.put(tp, OffsetSpec.latest()));
            if (request.isEmpty()) {
                return 0L;
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(request).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long lag = 0L;
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> entry : ends.entrySet()) {
                OffsetAndMetadata mark = committed.get(entry.getKey());
                long consumed = mark == null ? 0L : mark.offset();
                lag += Math.max(0L, entry.getValue().offset() - consumed);
            }
            return lag;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // 읽지 못한 것을 0 으로 돌려주면 안 된다. 랙을 빼지 않은 복원값은 그만큼 재고가 많아진다
            log.error("[KAFKA_LAG_READ_FAILED] topic={}, group={}, error={}", topic, groupId, e.toString());
            return -1L;
        }
    }
}
