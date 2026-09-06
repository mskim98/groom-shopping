package groom.backend.interfaces.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.PaymentCompensation;
import groom.backend.domain.payment.model.enums.CompensationStatus;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.domain.payment.repository.PaymentCompensationRepository;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T5-2·T5-3 다중 인스턴스 선점 통합 테스트 (실 PostgreSQL 필요).
 *
 * <p>SKIP LOCKED 선점 조회가 두 트랜잭션 사이에서 같은 행을 중복 할당하지 않는지(T5-2),
 * PROCESSING 에 멈춘 죽은 인스턴스의 행을 시간 경과 후 회수하는지(T5-3) 검증한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 실행 중인 앱과 Kafka Streams 상태 디렉토리 락 충돌 방지(이 테스트는 Streams 불필요)
@TestPropertySource(properties = "spring.kafka.streams.auto-startup=false")
@DisplayName("보상 선점 조회 통합 테스트(T5)")
class PaymentCompensationAcquireIntegrationTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private PaymentCompensationRepository compensationRepository;
    @Autowired
    private SpringDataPaymentCompensationRepository jpaRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    private UUID orderId;
    private UUID paymentId;
    private final List<UUID> seededIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // payment_compensation.payment_id 는 FK 라 유효한 Payment 가 있어야 한다.
        // FK 는 존재만 요구하고 유일성은 요구하지 않으므로 이 한 건을 모든 시드 행이 공유한다
        Order order = orderRepository.save(Order.builder()
                .userId(1L).couponId(null).status(OrderStatus.PENDING).build());
        orderId = order.getId();
        Payment payment = paymentRepository.save(Payment.builder()
                .order(order).userId(1L).amount(1000)
                .orderName("T5 선점 테스트").customerName("tester").method(PaymentMethod.CARD)
                .build());
        paymentId = payment.getId();
    }

    @AfterEach
    void tearDown() {
        if (!seededIds.isEmpty()) {
            jpaRepository.deleteAllById(seededIds);
        }
        paymentRepository.deleteById(paymentId);
        orderRepository.deleteById(orderId);
    }

    private PaymentCompensation seedCompensation() {
        PaymentCompensation compensation = jpaRepository.save(PaymentCompensation.builder()
                .paymentId(paymentId)
                .paymentKey("pk-" + UUID.randomUUID())
                .amount(1000)
                .reason("DB 후처리 실패")
                .maxRetryCount(5)
                .build());
        seededIds.add(compensation.getId());
        return compensation;
    }

    // ── T5-2: 두 스레드 동시 선점 → 교집합 0 ──

    @Test
    @DisplayName("대기 200건에 두 스레드가 동시에 선점하면 겹치는 행 없이 나뉘어 PROCESSING 으로 갱신된다")
    void concurrentAcquire_noOverlap() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            seedCompensation();
        }
        // 시드 시각(builder 가 찍는 nextRetryAt = 그 시점의 now())을 확실히 지나도록 여유를 둔다
        LocalDateTime now = LocalDateTime.now().plusSeconds(1);
        LocalDateTime staleThreshold = now.minusSeconds(90);
        int limit = 120; // 200건을 두 스레드(각 120)가 나눠 가지도록: 합이 총량을 넘어야 전량이 소진된다

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicReference<List<PaymentCompensation>> resultA = new AtomicReference<>();
        AtomicReference<List<PaymentCompensation>> resultB = new AtomicReference<>();

        executor.submit(() -> runAcquire(startLatch, doneLatch, resultA, now, staleThreshold, limit));
        executor.submit(() -> runAcquire(startLatch, doneLatch, resultB, now, staleThreshold, limit));

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("두 스레드가 시간 안에 끝난다(락 대기로 무한정 멈추지 않는다)").isTrue();
        assertThat(resultA.get()).as("스레드 A 결과").isNotNull();
        assertThat(resultB.get()).as("스레드 B 결과").isNotNull();

        Set<UUID> idsA = toIds(resultA.get());
        Set<UUID> idsB = toIds(resultB.get());
        Set<UUID> intersection = new HashSet<>(idsA);
        intersection.retainAll(idsB);
        Set<UUID> union = new HashSet<>(idsA);
        union.addAll(idsB);

        assertThat(intersection).as("두 스레드가 같은 행을 중복 선점하지 않는다").isEmpty();
        assertThat(resultA.get().size()).isLessThanOrEqualTo(limit);
        assertThat(resultB.get().size()).isLessThanOrEqualTo(limit);
        assertThat(union.size()).isLessThanOrEqualTo(200);
        assertThat(union).as("적어도 한 건은 선점된다").isNotEmpty();

        List<PaymentCompensation> reloaded = jpaRepository.findAllById(union);
        assertThat(reloaded).allSatisfy(c ->
                assertThat(c.getStatus()).as("선점된 행은 DB 상 PROCESSING 이다")
                        .isEqualTo(CompensationStatus.PROCESSING));
    }

    private void runAcquire(CountDownLatch startLatch, CountDownLatch doneLatch,
                             AtomicReference<List<PaymentCompensation>> resultHolder,
                             LocalDateTime now, LocalDateTime staleThreshold, int limit) {
        try {
            startLatch.await();
            List<PaymentCompensation> result = executeInNewTransaction(() ->
                    compensationRepository.findRetryable(now, staleThreshold, limit));
            resultHolder.set(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doneLatch.countDown();
        }
    }

    private Set<UUID> toIds(List<PaymentCompensation> list) {
        return list.stream().map(PaymentCompensation::getId).collect(Collectors.toSet());
    }

    // ── T5-3: PROCESSING 회수 ──

    @Test
    @DisplayName("PROCESSING 이 2분 전에 멈춘 행은 회수되고, 방금 갱신된 행은 회수되지 않는다")
    void staleProcessingIsRecovered_freshIsNot() {
        PaymentCompensation stale = seedCompensation();
        stale.markProcessing();
        jpaRepository.save(stale);
        // @UpdateTimestamp 는 save 때마다 now() 로 덮으므로, save 가 끝난 뒤 별도 네이티브 UPDATE 로
        // updated_at 만 과거로 되돌려 "죽은 인스턴스가 오래 전에 선점한 채 멈췄다" 를 재현한다
        setUpdatedAtDirectly(stale.getId(), LocalDateTime.now().minusMinutes(2));

        PaymentCompensation fresh = seedCompensation();
        fresh.markProcessing();
        jpaRepository.save(fresh); // updated_at 은 이 save 시점(now)으로 남는다

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleThreshold = now.minusSeconds(90); // 기본 배치 주기 30초의 3배

        List<PaymentCompensation> recovered = executeInNewTransaction(() ->
                compensationRepository.findRetryable(now, staleThreshold, 50));

        Set<UUID> recoveredIds = toIds(recovered);
        assertThat(recoveredIds).as("2분 전에 멈춘 PROCESSING 행은 회수 대상이다").contains(stale.getId());
        assertThat(recoveredIds).as("방금 갱신된 PROCESSING 행은 회수 대상이 아니다").doesNotContain(fresh.getId());
    }

    private void setUpdatedAtDirectly(UUID id, LocalDateTime updatedAt) {
        executeInNewTransaction(() -> entityManager
                .createNativeQuery("UPDATE payment_compensation SET updated_at = :updatedAt WHERE id = :id")
                .setParameter("updatedAt", updatedAt)
                .setParameter("id", id)
                .executeUpdate());
    }

    private <T> T executeInNewTransaction(Supplier<T> action) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return txTemplate.execute(status -> action.get());
    }
}
