package groom.backend.infrastructure.kafka;

import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 보관 기간이 지난 PUBLISHED outbox 행을 지운다. 지우지 않으면 결제 수만큼 표가 자라 폴러 · 대사의 조회와 인덱스가 느려진다
// 한 문장에 batch-size 건까지만 지우고(행 잠금과 WAL 이 한꺼번에 몰리지 않게) 덜 지운 문장이 나올 때까지 되풀이한다
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOutboxPurgeJob {

    private final PaymentOutboxRepository outboxRepository;

    // 발행 뒤 이만큼 지난 행만 지운다. 너무 짧으면 READY 대사(재발행 판단)가 보는 발행 기록이 사라지고, 너무 길면 표가 그만큼 커진다
    @Value("${payment.outbox.retention-hours:24}")
    private long retentionHours = 24;

    // 한 문장이 지우는 행 수. 너무 크면 한 트랜잭션의 잠금 · 로그가 커지고, 너무 작으면 문장 수가 늘어 끝나는 데 오래 걸린다
    @Value("${payment.outbox.purge-batch-size:1000}")
    private int batchSize = 1000;

    @Scheduled(fixedDelayString = "${payment.outbox.purge-interval-ms:600000}", initialDelayString = "${payment.outbox.purge-interval-ms:600000}")
    public void purge() {
        try {
            purgeOnce();
        } catch (Exception e) {
            // 다음 회차에 다시 지운다. 삭제 실패가 다른 예약 작업을 막으면 안 된다
            log.error("[OUTBOX_PURGE_FAILED] error={}", e.getMessage(), e);
        }
    }

    /**
     * 보관 기간이 지난 PUBLISHED 행을 모두 지운다
     *
     * @return 지운 행 수
     */
    public int purgeOnce() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(retentionHours);
        int total = 0;
        int deleted;
        do {
            deleted = outboxRepository.deletePublishedBefore(cutoff, batchSize);
            total += deleted;
        } while (deleted >= batchSize);
        if (total > 0) {
            log.info("[OUTBOX_PURGED] deleted={}, cutoff={}", total, cutoff);
        }
        return total;
    }
}
