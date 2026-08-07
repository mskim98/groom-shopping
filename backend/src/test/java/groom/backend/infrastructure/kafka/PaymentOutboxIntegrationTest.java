package groom.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.interfaces.payment.persistence.PaymentOutboxJpaEntity;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentOutboxRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 7.1 결제 Outbox 통합 테스트 (실 PostgreSQL + Kafka 필요).
 *
 * <p>PENDING 으로 적재한 이벤트를 publisher 가 Kafka 로 발행하고 PUBLISHED 로 전이시키는지 검증한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("결제 Outbox 발행 통합 테스트")
class PaymentOutboxIntegrationTest {

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;
    @Autowired
    private PaymentEventPublisher paymentEventPublisher;
    @Autowired
    private SpringDataPaymentOutboxRepository springDataPaymentOutboxRepository;

    private UUID outboxId;

    @AfterEach
    void tearDown() {
        if (outboxId != null) {
            springDataPaymentOutboxRepository.deleteById(outboxId);
        }
    }

    @Test
    @DisplayName("PENDING 이벤트가 발행되면 PUBLISHED 로 전이된다")
    void publishPending_marksPublished() {
        // given - PENDING 이벤트 적재
        PaymentOutbox saved = paymentOutboxRepository.save(
                PaymentOutbox.create(UUID.randomUUID(), "PAYMENT_COMPLETED",
                        "{\"paymentId\":\"" + UUID.randomUUID() + "\"}"));
        outboxId = saved.getId();
        assertThat(saved.getStatus()).isEqualTo(PaymentOutbox.OutboxStatus.PENDING);

        // when - publisher 가 PENDING 행을 Kafka 로 발행
        paymentEventPublisher.publishPending();

        // then - DB 상태가 PUBLISHED 로 전이
        PaymentOutboxJpaEntity reloaded = springDataPaymentOutboxRepository.findById(outboxId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentOutbox.OutboxStatus.PUBLISHED.name());
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }
}
