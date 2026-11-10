package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.infrastructure.product.StockWarmUpRunner;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 보관 기간이 지난 PUBLISHED 행만 묶음 단위로 지우고, PENDING 행과 보관 기간 안의 행은 남기는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.outbox.retention-hours=24",
        "payment.outbox.purge-batch-size=1000"
})
@DisplayName("Outbox 발행 완료 행 삭제")
class PaymentOutboxPurgeJobTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private PaymentOutboxPurgeJob job;
    @Autowired
    private JdbcTemplate jdbc;

    private final String marker = "purge-" + UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM payment_outbox WHERE event_type = ?", marker);
    }

    private List<UUID> seed(int count, String status, LocalDateTime publishedAt) {
        List<UUID> ids = new ArrayList<>();
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            args.add(new Object[] {id, UUID.randomUUID(), marker, "{}", status, Timestamp.valueOf(LocalDateTime.now().minusDays(10)),
                    publishedAt == null ? null : Timestamp.valueOf(publishedAt)});
        }
        jdbc.batchUpdate("INSERT INTO payment_outbox (id, aggregate_id, event_type, payload, status, created_at, published_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", args);
        return ids;
    }

    private int countOf(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE event_type = ? AND status = ?", Integer.class, marker, status);
    }

    @Test
    @DisplayName("보관 기간이 지난 PUBLISHED 2,500행은 묶음 1,000건씩 한 번의 purgeOnce 로 모두 지워진다")
    void purgeOnce_오래된_PUBLISHED_2500행을_한번에_모두_지운다() {
        seed(2500, "PUBLISHED", LocalDateTime.now().minusHours(25));

        int deleted = job.purgeOnce();

        assertThat(deleted).isGreaterThanOrEqualTo(2500);
        assertThat(countOf("PUBLISHED")).isZero();
    }

    @Test
    @DisplayName("PENDING 행은 나이와 무관하게 남고 보관 기간 안의 PUBLISHED 행도 남는다")
    void purgeOnce_PENDING과_보관기간_안의_PUBLISHED는_남긴다() {
        seed(5, "PENDING", null);
        seed(5, "PUBLISHED", LocalDateTime.now().minusHours(23));
        seed(5, "PUBLISHED", LocalDateTime.now().minusHours(25));

        job.purgeOnce();

        assertThat(countOf("PENDING")).isEqualTo(5);
        assertThat(countOf("PUBLISHED")).as("보관 기간 안의 행만 남는다").isEqualTo(5);
    }

    @Test
    @DisplayName("purgeOnce 두 번을 동시에 불러도 오류 없이 각 행을 한 번씩만 지운다")
    void purgeOnce_동시_두_호출은_오류없이_각_행을_한번씩만_지운다() throws Exception {
        seed(2500, "PUBLISHED", LocalDateTime.now().minusHours(25));
        int expected = jdbc.queryForObject("SELECT count(*) FROM payment_outbox WHERE status = 'PUBLISHED' AND published_at < ?",
                Integer.class, Timestamp.valueOf(LocalDateTime.now().minusHours(24)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return job.purgeOnce();
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return job.purgeOnce();
            });
            start.countDown();

            int deleted = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);

            assertThat(deleted).isEqualTo(expected);
            assertThat(countOf("PUBLISHED")).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
