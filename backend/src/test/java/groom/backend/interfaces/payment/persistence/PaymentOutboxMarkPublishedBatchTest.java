package groom.backend.interfaces.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 확인된 행을 한 문장으로 PUBLISHED 표시하는 SQL 자체를 실제 DB 로 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.outbox.poll-delay-ms=3600000"
})
@DisplayName("Outbox 배치 PUBLISHED 표시")
class PaymentOutboxMarkPublishedBatchTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private PaymentOutboxRepository outboxRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> aggregates = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UUID aggregate : aggregates) {
            jdbc.update("DELETE FROM payment_outbox WHERE aggregate_id = ?", aggregate);
        }
    }

    // 임대 중인 PENDING 행으로 적재한다 - 폴러가 집은 행과 같은 모양
    private UUID seedLeased() {
        UUID aggregate = UUID.randomUUID();
        aggregates.add(aggregate);
        PaymentOutbox outbox = outboxRepository.save(PaymentOutbox.create(aggregate, "PaymentApproved", "{}"));
        jdbc.update("UPDATE payment_outbox SET claimed_until = now() + interval '60 seconds' WHERE id = ?", outbox.getId());
        return outbox.getId();
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM payment_outbox WHERE id = ?", String.class, id);
    }

    private boolean leased(UUID id) {
        return jdbc.queryForObject("SELECT claimed_until IS NOT NULL FROM payment_outbox WHERE id = ?", Boolean.class, id);
    }

    @Test
    @DisplayName("넘긴 행만 PUBLISHED 로 표시하고 발행 시각을 채우며 임대를 비우고 넘기지 않은 행은 그대로 둔다")
    void markPublished_넘긴_행만_표시하고_임대를_비운다() {
        UUID a = seedLeased();
        UUID b = seedLeased();
        UUID untouched = seedLeased();

        outboxRepository.markPublished(List.of(a, b));

        assertThat(statusOf(a)).isEqualTo("PUBLISHED");
        assertThat(statusOf(b)).isEqualTo("PUBLISHED");
        assertThat(leased(a)).isFalse();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM payment_outbox WHERE id = ?", Boolean.class, a)).isTrue();
        assertThat(statusOf(untouched)).isEqualTo("PENDING");
        assertThat(leased(untouched)).isTrue();
    }

    @Test
    @DisplayName("이미 PUBLISHED 인 행의 발행 시각은 다시 쓰지 않고 빈 목록은 아무 일도 하지 않는다")
    void markPublished_이미_표시된_행은_건드리지_않고_빈_목록은_무시한다() {
        UUID done = seedLeased();
        outboxRepository.markPublished(List.of(done));
        Object publishedAt = jdbc.queryForObject("SELECT published_at FROM payment_outbox WHERE id = ?", Object.class, done);

        outboxRepository.markPublished(List.of(done));
        outboxRepository.markPublished(List.of());

        assertThat(jdbc.queryForObject("SELECT published_at FROM payment_outbox WHERE id = ?", Object.class, done))
                .isEqualTo(publishedAt);
    }
}
