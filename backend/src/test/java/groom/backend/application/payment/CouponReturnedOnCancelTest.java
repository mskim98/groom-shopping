package groom.backend.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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

// 쿠폰을 쓴 DONE 결제를 취소하면 같은 트랜잭션에서 쿠폰이 다시 쓸 수 있게 된다 - 만료된 쿠폰과 Toss 취소 실패는 그대로 사용 상태다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.compensation.retry-delay-ms=600000"
})
@DisplayName("결제 취소 시 쿠폰 반환")
class CouponReturnedOnCancelTest {

    private static final long BUYER = 1L;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화
    @MockBean
    private TossPaymentClient tossPaymentClient; // 취소 호출을 스텁한다(기본은 성공)

    @Autowired
    private PaymentEventConsumer consumer;
    @Autowired
    private PaymentApplicationService paymentApplicationService;
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
    private LocalDate expireDate;
    private final List<Payment> payments = new ArrayList<>();
    private final List<String> cacheKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixture = new PaymentFixture(productRepository, orderRepository, paymentRepository, jdbc);
        product = fixture.product(10);
        expireDate = LocalDate.now().plusDays(30);
        coupon = couponRepository.save(Coupon.builder().name("cancel-" + UUID.randomUUID()).quantity(10L)
                .amount(300).type(CouponType.DISCOUNT).expireDate(expireDate).build());
    }

    @AfterEach
    void tearDown() {
        payments.forEach(fixture::cleanupPaymentOnly);
        redis.delete(cacheKeys);
        couponRepository.deleteById(coupon.getId());
        fixture.deleteProduct(product);
    }

    @Test
    @DisplayName("쿠폰을 쓴 DONE 결제를 취소하면 쿠폰이 다시 활성이고 만료일이 복원되며 쿠폰 캐시가 커밋 뒤 비워진다")
    void 취소_성공_쿠폰이_다시_쓸_수_있다() throws Exception {
        CouponIssue issue = issue(true);
        Payment payment = approvedWithCoupon(issue);
        assertThat(isActive(issue)).as("승인 뒤").isFalse();
        String itemKey = seedCache("coupon-item-cache::" + issue.getId());
        String listKey = seedCache("user-coupons-list-cache::" + BUYER);

        paymentApplicationService.cancelPayment(payment.getId(), "단순 변심");

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("CANCELED");
        assertThat(isActive(issue)).as("취소 뒤 쿠폰 활성").isTrue();
        assertThat(deletedAt(issue)).as("취소 뒤 만료일").isEqualTo(expireDate.atStartOfDay());
        assertThat(redis.hasKey(itemKey)).as("쿠폰 단건 캐시").isFalse();
        assertThat(redis.hasKey(listKey)).as("쿠폰 목록 캐시").isFalse();
    }

    @Test
    @DisplayName("쿠폰 만료일이 지난 뒤 취소하면 결제는 취소되지만 쿠폰은 사용 상태 그대로다")
    void 취소_만료된_쿠폰은_사용_상태로_남는다() throws Exception {
        CouponIssue issue = issue(true);
        Payment payment = approvedWithCoupon(issue);
        LocalDateTime usedAt = deletedAt(issue);
        jdbc.update("UPDATE coupon SET expire_date = ? WHERE id = ?", LocalDate.now().minusDays(1), coupon.getId());

        paymentApplicationService.cancelPayment(payment.getId(), "단순 변심");

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("CANCELED");
        assertThat(isActive(issue)).isFalse();
        assertThat(deletedAt(issue)).as("사용 시각이 그대로").isEqualTo(usedAt);
    }

    @Test
    @DisplayName("Toss 취소가 실패하면 쿠폰은 사용 상태 그대로이고 결제는 DONE 이다")
    void 취소_실패_쿠폰은_사용_상태로_남는다() throws Exception {
        CouponIssue issue = issue(true);
        Payment payment = approvedWithCoupon(issue);
        when(tossPaymentClient.cancelPayment(any(), any(), any())).thenThrow(new RuntimeException("toss down"));

        assertThatThrownBy(() -> paymentApplicationService.cancelPayment(payment.getId(), "단순 변심"))
                .hasMessageContaining("결제 취소에 실패했습니다");

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("DONE");
        assertThat(isActive(issue)).isFalse();
    }

    @Test
    @DisplayName("쿠폰 없는 주문의 취소는 이전처럼 끝나고 다른 사용한 쿠폰은 그대로다")
    void 쿠폰없는_주문_취소_다른_쿠폰은_그대로다() throws Exception {
        CouponIssue otherUsed = issue(false);
        Payment payment = payments(fixture.payment(product, 1));
        approve(payment);

        paymentApplicationService.cancelPayment(payment.getId(), "단순 변심");

        assertThat(fixture.statusOf(payment.getId())).isEqualTo("CANCELED");
        assertThat(isActive(otherUsed)).isFalse();
    }

    private Payment approvedWithCoupon(CouponIssue issue) throws Exception {
        Payment payment = payments(fixture.payment(product, 1));
        jdbc.update("UPDATE orders SET coupon_id = ? WHERE id = ?", issue.getId(), payment.getOrderId());
        approve(payment);
        assertThat(fixture.statusOf(payment.getId())).as("승인 결과").isEqualTo("DONE");
        return payment;
    }

    private void approve(Payment payment) throws Exception {
        String paymentKey = "pk-" + UUID.randomUUID();
        fixture.markReady(payment.getId(), paymentKey);
        int amount = PaymentFixture.PRICE;
        consumer.consume(PaymentFixture.record(objectMapper, new PaymentApprovedEvent(payment.getId(),
                payment.getOrderId(), amount, PaymentFixture.tossResponse(paymentKey, amount), List.of())));
    }

    private Payment payments(Payment payment) {
        payments.add(payment);
        return payment;
    }

    // 발급기와 같이 만료일 0시를 deletedAt 으로 둔 활성 발급 행(active=false 면 이미 사용한 행)
    private CouponIssue issue(boolean active) {
        LocalDateTime deletedAt = active ? expireDate.atStartOfDay() : LocalDateTime.now().minusHours(1);
        return couponIssueRepository.save(CouponIssue.builder().coupon(coupon).userId(BUYER).isActive(active)
                .createdAt(LocalDateTime.now().minusDays(1)).deletedAt(deletedAt).build());
    }

    private String seedCache(String key) {
        redis.opsForValue().set(key, "seed");
        cacheKeys.add(key);
        return key;
    }

    private boolean isActive(CouponIssue issue) {
        return jdbc.queryForObject("SELECT is_active FROM coupon_issue WHERE id = ?", Boolean.class, issue.getId());
    }

    private LocalDateTime deletedAt(CouponIssue issue) {
        return jdbc.queryForObject("SELECT deleted_at FROM coupon_issue WHERE id = ?", LocalDateTime.class,
                issue.getId());
    }
}
