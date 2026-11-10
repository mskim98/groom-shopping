package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentOutbox;
import groom.backend.domain.payment.repository.PaymentOutboxRepository;
import groom.backend.domain.payment.repository.PaymentRepository;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

// 여러 인스턴스가 같은 행을 동시에 집지 않는지 본다 - Outbox 폴러는 FOR UPDATE SKIP LOCKED 로, READY 대사는 집는 즉시 되살려 다른 회차가 못 집게
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("Outbox 폴러 · READY 대사의 다중 인스턴스 행 선점")
class PaymentOutboxSkipLockedTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private PaymentOutboxRepository outboxRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository springPaymentRepository;

    private final List<UUID> aggregates = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();
    private UUID productId;
    private PaymentFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, springPaymentRepository, jdbc);
    }

    @AfterEach
    void tearDown() {
        for (UUID aggregate : aggregates) {
            jdbc.update("DELETE FROM payment_outbox WHERE aggregate_id = ?", aggregate);
        }
        for (Payment payment : payments) {
            fixture.cleanupPaymentOnly(payment);
        }
        if (productId != null) {
            fixture.deleteProduct(productId);
        }
    }

    // 다른 시험이 남긴 PENDING 행보다 앞에 오도록 오래된 created_at 으로 적재한다
    private void seedPending(int count) {
        for (int i = 0; i < count; i++) {
            UUID aggregate = UUID.randomUUID();
            aggregates.add(aggregate);
            PaymentOutbox outbox = outboxRepository.save(PaymentOutbox.create(aggregate, "PaymentApproved", "{}"));
            jdbc.update("UPDATE payment_outbox SET created_at = ? WHERE id = ?",
                    LocalDateTime.of(2000, 1, 1, 0, 0).plusSeconds(i), outbox.getId());
        }
    }

    @Test
    @DisplayName("두 트랜잭션이 동시에 폴링하면 서로 다른 행을 받는다")
    void claimPendingBatch_두_트랜잭션_동시_폴링은_서로_다른_행을_받는다() throws Exception {
        seedPending(10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        try {
            Future<Set<UUID>> first = pool.submit(() -> transactionTemplate.execute(status -> {
                Set<UUID> ids = idsOf(outboxRepository.claimPendingBatch(5, 60000L));
                firstHolds.countDown();
                await(secondDone); // 첫 트랜잭션은 잠금을 쥔 채 둘째가 끝나길 기다린다
                return ids;
            }));
            assertThat(firstHolds.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Set<UUID>> second = pool.submit(() -> transactionTemplate.execute(status -> {
                Set<UUID> ids = idsOf(outboxRepository.claimPendingBatch(5, 60000L));
                secondDone.countDown();
                return ids;
            }));

            Set<UUID> firstIds = first.get(15, TimeUnit.SECONDS);
            Set<UUID> secondIds = second.get(15, TimeUnit.SECONDS);

            assertThat(firstIds).hasSize(5);
            assertThat(secondIds).hasSize(5);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("READY 대사는 집은 결제를 다음 조회에 다시 돌려주지 않는다")
    void findStaleReady_집은_결제는_다음_조회에_다시_나오지_않는다() {
        productId = fixture.product(10);
        for (int i = 0; i < 4; i++) {
            Payment payment = fixture.payment(productId, 1);
            payments.add(payment);
            jdbc.update("UPDATE payment SET status = 'READY', payment_key = ?, updated_at = ? WHERE id = ?",
                    "pk-" + payment.getId(), LocalDateTime.of(2000, 1, 1, 0, 0).plusSeconds(payments.size()), payment.getId());
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);

        Set<UUID> firstIds = payIds(paymentRepository.findStaleReady(cutoff, 2));
        Set<UUID> secondIds = payIds(paymentRepository.findStaleReady(cutoff, 2));
        Set<UUID> thirdIds = payIds(paymentRepository.findStaleReady(cutoff, 2));

        Set<UUID> ours = new HashSet<>();
        payments.forEach(p -> ours.add(p.getId()));
        assertThat(firstIds).hasSize(2).isSubsetOf(ours);
        assertThat(secondIds).hasSize(2).isSubsetOf(ours);
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        assertThat(thirdIds).doesNotContainAnyElementsOf(ours);
    }

    private static Set<UUID> idsOf(List<PaymentOutbox> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.getId()));
        return ids;
    }

    private static Set<UUID> payIds(List<Payment> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.getId()));
        return ids;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
