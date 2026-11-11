package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.event.PaymentApprovedEvent;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.entity.CouponIssue;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.infrastructure.kafka.PaymentEventConsumer;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 주문에 붙은 쿠폰은 결제를 DONE 으로 바꾸는 승인 트랜잭션에서 소모된다 - 소모하지 못하면 승인이 롤백되고 보상 경로가 돈다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        // 배경 보상 배치가 이 시험의 보상 행을 가져가지 않게 늦춘다
        "payment.compensation.retry-delay-ms=600000"
})
@DisplayName("승인 트랜잭션 안의 쿠폰 소모")
class CouponConsumedOnApprovalTest {

    private static final long BUYER = 1L;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private TossPaymentClient tossPaymentClient; // 보상 기록이 외부 호출을 하지 않게 막는다

    @Autowired
    private PaymentEventConsumer consumer;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    private PaymentFixture fixture;
    private UUID product;
    private Coupon coupon;
    private final List<Payment> payments = new ArrayList<>();
    private final List<String> cacheKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        product = fixture.product(10);
        coupon = couponRepository.save(Coupon.builder().name("approval-" + UUID.randomUUID()).quantity(10L)
                .amount(300).type(CouponType.DISCOUNT).expireDate(LocalDate.now().plusDays(30)).build());
    }

    @AfterEach
    void tearDown() {
        payments.forEach(fixture::cleanupPaymentOnly);
        redis.delete(cacheKeys);
        // 쿠폰 발급 행은 쿠폰 삭제의 CASCADE 로 함께 지워진다
        couponRepository.deleteById(coupon.getId());
        fixture.deleteProduct(product);
    }

    @Test
    @DisplayName("쿠폰을 쓴 주문을 컨슈머 경로로 승인하면 결제 DONE · 주문 CONFIRMED 와 같은 커밋에서 쿠폰 발급 행이 사용 처리되고 쿠폰 캐시가 비워진다")
    void 쿠폰_주문_승인_쿠폰이_사용_처리된다() throws Exception {
        CouponIssue issue = issue(BUYER, true, LocalDateTime.now().plusDays(1));
        String itemKey = seedCache(issue);
        Payment payment = paymentWithCoupon(issue);

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        approve(payment);

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(fixture.orderStatusOf(payment.getOrderId())).isEqualTo("CONFIRMED");
        assertThat(isActive(issue)).as("승인 뒤 쿠폰 발급 행 활성").isFalse();
        assertThat(usedAt(issue)).as("승인 뒤 쿠폰 사용 시각").isAfter(before).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(redis.hasKey(itemKey)).as("커밋 뒤 쿠폰 단건 캐시").isFalse();
    }

    @Test
    @DisplayName("쿠폰 없는 주문은 이전처럼 승인되고 다른 쿠폰 발급 행은 그대로다")
    void 쿠폰없는_주문_승인_다른_쿠폰은_그대로다() throws Exception {
        CouponIssue untouched = issue(BUYER, true, LocalDateTime.now().plusDays(1));
        Payment payment = payments(fixture.payment(product, 1));

        approve(payment);

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(isActive(untouched)).isTrue();
    }

    @Test
    @DisplayName("이미 사용한 쿠폰으로 승인하면 롤백되어 결제는 FAILED · 주문은 PENDING · 재고는 그대로이고 보상이 1건 기록된다")
    void 이미_사용한_쿠폰_승인_롤백되고_보상이_기록된다() throws Exception {
        CouponIssue issue = issue(BUYER, false, LocalDateTime.now().minusHours(1));
        Payment payment = paymentWithCoupon(issue);

        approve(payment);

        assertRolledBack(payment, issue);
    }

    @Test
    @DisplayName("다른 사용자의 쿠폰으로 승인하면 롤백되고 그 쿠폰은 활성 그대로다")
    void 남의_쿠폰_승인_롤백되고_쿠폰은_그대로다() throws Exception {
        long otherUser = jdbc.queryForObject("SELECT min(id) FROM users WHERE id <> ?", Long.class, BUYER);
        CouponIssue issue = issue(otherUser, true, LocalDateTime.now().plusDays(1));
        String itemKey = seedCache(issue);
        Payment payment = paymentWithCoupon(issue);

        approve(payment);

        assertRolledBack(payment, issue);
        assertThat(isActive(issue)).isTrue();
        assertThat(redis.hasKey(itemKey)).as("롤백 뒤 쿠폰 단건 캐시").isTrue();
    }

    @Test
    @DisplayName("승인 시각에 만료된 쿠폰으로 승인하면 롤백되고 만료일 값이 사용 시각으로 덮이지 않는다")
    void 만료된_쿠폰_승인_롤백되고_만료일은_그대로다() throws Exception {
        LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(5).withNano(0);
        CouponIssue issue = issue(BUYER, true, expiredAt);
        Payment payment = paymentWithCoupon(issue);

        approve(payment);

        assertRolledBack(payment, issue);
        assertThat(isActive(issue)).isTrue();
        assertThat(usedAt(issue)).isEqualTo(expiredAt);
    }

    @Test
    @DisplayName("같은 쿠폰을 쓴 두 주문을 동시에 승인하면 결제 DONE 은 정확히 1건이고 나머지는 보상 1건이며 쿠폰은 한 번만 사용된다")
    void 같은_쿠폰_두_주문_동시_승인_한_건만_DONE_이다() throws Exception {
        CouponIssue issue = issue(BUYER, true, LocalDateTime.now().plusDays(1));
        Payment first = paymentWithCoupon(issue);
        Payment second = paymentWithCoupon(issue);

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (Payment payment : List.of(first, second)) {
                done.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    approve(payment);
                    return null;
                }));
            }
            for (Future<?> f : done) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> statuses = List.of(fixture.statusOf(first.getId()), fixture.statusOf(second.getId()));
        assertThat(statuses).as("두 결제 상태").containsExactlyInAnyOrder("DONE", "FAILED");
        assertThat(fixture.compensationCountOf(first.getId()) + fixture.compensationCountOf(second.getId()))
                .as("보상 기록 합").isEqualTo(1);
        assertThat(isActive(issue)).isFalse();
        assertThat(fixture.stockOf(product)).as("DONE 1건의 재고 차감").isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE coupon_id = ? AND status = 'CONFIRMED'",
                Integer.class, issue.getId())).as("확정된 주문 수").isEqualTo(1);
    }

    private void assertRolledBack(Payment payment, CouponIssue issue) {
        assertThat(fixture.statusOf(payment.getId())).as("롤백 뒤 결제 상태").isEqualTo("FAILED");
        assertThat(fixture.failureCodeOf(payment.getId())).isEqualTo("COUPON_NOT_USABLE");
        assertThat(fixture.orderStatusOf(payment.getOrderId())).as("롤백 뒤 주문 상태").isEqualTo("PENDING");
        assertThat(fixture.stockOf(product)).as("롤백 뒤 재고").isEqualTo(10);
        assertThat(fixture.compensationCountOf(payment.getId())).as("Toss 취소 보상 기록").isEqualTo(1);
    }

    private void approve(Payment payment) throws Exception {
        String paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
        int amount = PaymentFixture.PRICE;
        consumer.consume(PaymentFixture.record(objectMapper, new PaymentApprovedEvent(payment.getId(),
                payment.getOrderId(), amount, PaymentFixture.tossResponse(paymentKey, amount), List.of())));
    }

    private Payment paymentWithCoupon(CouponIssue issue) {
        Payment payment = payments(fixture.payment(product, 1));
        jdbc.update("UPDATE orders SET coupon_id = ? WHERE id = ?", issue.getId(), payment.getOrderId());
        return payment;
    }

    private Payment payments(Payment payment) {
        payments.add(payment);
        return payment;
    }

    private CouponIssue issue(long userId, boolean active, LocalDateTime deletedAt) {
        return couponIssueRepository.save(CouponIssue.builder().coupon(coupon).userId(userId).isActive(active)
                .createdAt(LocalDateTime.now().minusDays(1)).deletedAt(deletedAt).build());
    }

    // 쿠폰 단건 캐시 키(coupon-item-cache::<발급 id>)에 값을 심어 커밋 뒤 제거를 본다
    private String seedCache(CouponIssue issue) {
        String key = "coupon-item-cache::" + issue.getId();
        redis.opsForValue().set(key, "seed");
        cacheKeys.add(key);
        return key;
    }

    private boolean isActive(CouponIssue issue) {
        return jdbc.queryForObject("SELECT is_active FROM coupon_issue WHERE id = ?", Boolean.class, issue.getId());
    }

    private LocalDateTime usedAt(CouponIssue issue) {
        return jdbc.queryForObject("SELECT deleted_at FROM coupon_issue WHERE id = ?", LocalDateTime.class,
                issue.getId());
    }
}
