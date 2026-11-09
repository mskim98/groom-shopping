package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 스케줄 주기는 늘려 두고 시험이 대사 메서드를 직접 부른다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.compensation.retry-delay-ms=600000",
        "payment.reconcile.delay-ms=3600000",
        "payment.reconcile.stale-after-ms=300000",
        "payment.reconcile.max-republish=2"
})
@DisplayName("READY 에 멈춘 결제 대사")
class PaymentReadyReconcilerTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private PaymentReadyReconciler reconciler;
    // 실제 기록은 그대로 하되 한 결제의 보상 기록만 실패시키는 지점으로도 쓴다
    @SpyBean
    private PaymentCompensationService compensationService;
    @Autowired
    private ProductStockRedisRepository productStockRedisRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private PaymentFixture fixture;
    private UUID productId;
    private Payment payment;
    private String paymentKey;

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        productId = fixture.product(10);
        payment = fixture.payment(productId, 1);
        paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
        fixture.cleanup(payment, productId); // payment_outbox · payment_compensation 행도 함께 지운다
    }

    // 결제 행의 updated_at 을 10분 전으로 밀어 대사 기준(stale-after-ms 5분)을 넘긴다
    private void makeStale() {
        jdbc.update("UPDATE payment SET updated_at = now() - interval '10 minutes' WHERE id = ?", payment.getId());
    }

    // 이 결제의 아웃박스 행을 주어진 상태와 재발행 횟수로 넣는다. PUBLISHED 는 10분 전에 발행된 것으로 둔다(대사 기준 5분보다 오래됨)
    private void insertOutbox(String status, int republishCount) {
        insertOutbox(status, republishCount, 10, "{}");
    }

    // PUBLISHED 의 발행 시각을 publishedMinutesAgo 분 전으로 두고 payload 도 정한다
    private void insertOutbox(String status, int republishCount, int publishedMinutesAgo, String payload) {
        jdbc.update("INSERT INTO payment_outbox (id, aggregate_id, event_type, payload, status, created_at, "
                        + "published_at, republish_count) "
                        + "VALUES (?, ?, 'PaymentApproved', ?, ?, now(), "
                        + "CASE WHEN ? = 'PUBLISHED' THEN now() - make_interval(mins => ?) ELSE NULL END, ?)",
                UUID.randomUUID(), payment.getId(), payload, status, status, publishedMinutesAgo, republishCount);
    }

    // 이 결제의 아웃박스 행 한 개를 status · republish_count · published_at 으로 읽는다
    private Map<String, Object> outboxRow() {
        return jdbc.queryForMap("SELECT status, republish_count, published_at FROM payment_outbox WHERE aggregate_id = ?",
                payment.getId());
    }

    @Test
    @DisplayName("보상 행이 있으면 FAILED 만 표시하고 outbox 와 보상 행은 그대로 둔다")
    void reconcile_보상행이_있으면_FAILED만_표시하고_outbox와_보상행은_그대로다() {
        // 후처리 실패 경로가 보상 의도는 남겼는데 FAILED 기록이 유실된 상태 - 환불은 배치 몫이라 새 보상 행을 만들면 안 된다
        compensationService.compensate(payment.getId(), paymentKey, PaymentFixture.PRICE, "시험: 후처리 실패");
        insertOutbox("PUBLISHED", 0);
        makeStale();

        reconciler.reconcileStaleReady();

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
        assertThat(fixture.failureCodeOf(payment.getId())).isEqualTo("DB_POST_PROCESS_FAILED");
        assertThat(fixture.compensationCountOf(payment.getId())).as("보상 행은 기존 1건 그대로").isEqualTo(1);
        assertThat(outboxRow()).containsEntry("status", "PUBLISHED").containsEntry("republish_count", 0);
    }

    @Test
    @DisplayName("PENDING outbox 면 아무것도 바꾸지 않는다")
    void reconcile_PENDING_outbox면_아무것도_바꾸지_않는다() {
        insertOutbox("PENDING", 0);
        makeStale();

        reconciler.reconcileStaleReady();

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("READY");
        assertThat(fixture.compensationCountOf(payment.getId())).isZero();
        assertThat(outboxRow()).containsEntry("status", "PENDING").containsEntry("republish_count", 0);
    }

    @Test
    @DisplayName("PUBLISHED outbox 가 상한 미만이면 PENDING 으로 되돌리고 재발행 횟수를 올린다")
    void reconcile_PUBLISHED_outbox가_상한_미만이면_PENDING으로_되돌리고_횟수를_올린다() {
        insertOutbox("PUBLISHED", 0);
        makeStale();

        reconciler.reconcileStaleReady();

        assertThat(outboxRow()).containsEntry("status", "PENDING").containsEntry("republish_count", 1)
                .containsEntry("published_at", null);
        assertThat(fixture.statusOf(payment.getId())).as("재발행만 하고 결제는 그대로").isEqualTo("READY");
        assertThat(fixture.compensationCountOf(payment.getId())).isZero();
    }

    @Test
    @DisplayName("재발행 상한에 닿으면 보상 행을 기록하고 FAILED 로 만든다")
    void reconcile_재발행_상한에_닿으면_보상_행을_기록하고_FAILED로_만든다() {
        insertOutbox("PUBLISHED", 2);
        makeStale();

        reconciler.reconcileStaleReady();

        assertThat(fixture.compensationCountOf(payment.getId())).as("환불 의도 보상 행").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payment_key FROM payment_compensation WHERE payment_id = ?",
                String.class, payment.getId())).isEqualTo(paymentKey);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
        assertThat(outboxRow()).as("상한에 닿은 outbox 는 다시 건드리지 않는다")
                .containsEntry("status", "PUBLISHED").containsEntry("republish_count", 2);
    }

    @Test
    @DisplayName("outbox 행이 없으면 보상 행을 기록하고 FAILED 로 만든다")
    void reconcile_outbox_행이_없으면_보상_행을_기록하고_FAILED로_만든다() {
        makeStale();

        reconciler.reconcileStaleReady();

        assertThat(fixture.compensationCountOf(payment.getId())).isEqualTo(1);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("갓 READY 가 된 결제는 건드리지 않는다")
    void reconcile_갓_READY가_된_결제는_건드리지_않는다() {
        // 대기 중인 확정 요청이 아직 이 결제를 보고 있을 수 있다 - updated_at 을 과거로 밀지 않는다
        insertOutbox("PUBLISHED", 2);

        reconciler.reconcileStaleReady();

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("READY");
        assertThat(fixture.compensationCountOf(payment.getId())).isZero();
        assertThat(outboxRow()).containsEntry("status", "PUBLISHED").containsEntry("republish_count", 2);
    }

    @Test
    @DisplayName("재발행 상한에 닿아 환불하는 경우 outbox payload 의 선점 항목을 Redis 에 되돌려 선점 전 값이 된다")
    void reconcile_상한에_닿아_환불하면_Redis_재고가_선점_전_값으로_돌아온다() {
        // confirm 이 재고 10 에서 1개를 선점해 Redis 가 9 가 된 상태
        productStockRedisRepository.initStock(productId, 9);
        insertOutbox("PUBLISHED", 2, 10, "{\"reservedItems\":[{\"productId\":\"" + productId + "\",\"quantity\":1}]}");
        makeStale();

        reconciler.reconcileStaleReady();

        assertThat(redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId))
                .as("복원 뒤 Redis 재고").isEqualTo("10");
        assertThat(fixture.compensationCountOf(payment.getId())).isEqualTo(1);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("방금 발행된 PUBLISHED 행은 상한 미만이어도 재발행하지 않고 결제도 그대로다")
    void reconcile_갓_PUBLISHED된_행은_재발행하지_않는다() {
        insertOutbox("PUBLISHED", 0, 0, "{}");
        makeStale();

        reconciler.reconcileStaleReady();

        assertThat(outboxRow()).containsEntry("status", "PUBLISHED").containsEntry("republish_count", 0);
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("READY");
    }

    @Test
    @DisplayName("방금 재발행된 행은 상한에 닿았어도 다음 회차까지 환불하지 않는다")
    void reconcile_갓_PUBLISHED된_상한_행은_환불하지_않는다() {
        insertOutbox("PUBLISHED", 2, 0, "{}");
        makeStale();

        reconciler.reconcileStaleReady();

        assertThat(fixture.compensationCountOf(payment.getId())).isZero();
        assertThat(fixture.statusOf(payment.getId())).isEqualTo("READY");
    }

    @Test
    @DisplayName("한 결제의 보상 기록이 실패해도 다음 결제는 계속 대사한다")
    void reconcile_한_건의_실패가_다음_건을_막지_않는다() {
        // 이 시험이 만든 결제만 본다 - 먼저 처리되는(더 오래된) 쪽의 compensate 가 던진다
        UUID secondProductId = fixture.product(10);
        Payment second = fixture.payment(secondProductId, 1);
        String secondKey = "pk-" + UUID.randomUUID();
        fixture.markReady(second.getId(), secondKey);
        try {
            jdbc.update("UPDATE payment SET updated_at = now() - interval '20 minutes' WHERE id = ?", payment.getId());
            jdbc.update("UPDATE payment SET updated_at = now() - interval '10 minutes' WHERE id = ?", second.getId());
            doThrow(new IllegalStateException("compensation store down"))
                    .when(compensationService).compensate(eq(payment.getId()), any(), any(), any());

            reconciler.reconcileStaleReady();

            assertThat(fixture.statusOf(payment.getId())).as("실패한 결제는 READY 그대로 다음 회차를 기다린다").isEqualTo("READY");
            assertThat(fixture.compensationCountOf(second.getId())).as("다음 결제는 처리됨").isEqualTo(1);
            assertThat(fixture.statusOf(second.getId())).isEqualTo("FAILED");
        } finally {
            fixture.cleanup(second, secondProductId);
        }
    }
}
