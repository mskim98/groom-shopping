package groom.backend.interfaces.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.payment.PaymentApplicationService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.domain.payment.model.Payment;
import groom.backend.domain.payment.model.enums.PaymentMethod;
import groom.backend.infrastructure.payment.TossPaymentClient;
import groom.backend.infrastructure.payment.dto.TossPaymentResponse;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.payment.persistence.SpringDataPaymentRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// 결제 조회 · 승인이 결제 주인에게만 되는지 본다 (남의 결제는 Toss 를 부르기 전에 막고 결제 상태를 바꾸지 않는다)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "payment.confirm.wait-timeout-ms=500",
        "payment.confirm.poll-interval-ms=50"
})
@DisplayName("결제 조회 · 승인의 소유자 검사")
class PaymentOwnershipTest {

    private static final int PRICE = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;
    @MockBean
    private TossPaymentClient tossPaymentClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PaymentApplicationService paymentApplicationService;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SpringDataPaymentRepository paymentRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> emails = new ArrayList<>();
    private String marker;
    private UserJpaEntity owner;
    private UserJpaEntity other;
    private String ownerToken;
    private String otherToken;
    private UUID productId;
    private Payment payment;
    private String paymentKey;

    @BeforeEach
    void setUp() throws Exception {
        marker = UUID.randomUUID().toString().substring(0, 8);
        owner = seedUser("owner", Role.ROLE_USER);
        other = seedUser("other", Role.ROLE_USER);
        ownerToken = login(owner);
        otherToken = login(other);
        productId = productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name("own-" + marker)
                .price(PRICE).stock(10).isActive(true).category("GENERAL").status("AVAILABLE").build()).getId();
        Order order = Order.builder().userId(owner.getId()).couponId(null).status(OrderStatus.PENDING).build();
        order.addOrderItem(OrderItem.builder().productId(productId).productName("own-" + marker).price(PRICE)
                .quantity(1).build());
        order.calculateAmounts();
        order = orderRepository.save(order);
        payment = paymentRepository.save(Payment.builder().order(order).userId(owner.getId()).amount(PRICE)
                .orderName("own-order-" + marker).customerName("owner").method(PaymentMethod.CARD).build());
        paymentKey = "pk-" + UUID.randomUUID();
        given(tossPaymentClient.confirmPayment(any(), anyString())).willReturn(tossResponse(paymentKey));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM payment_compensation WHERE payment_id = ?", payment.getId());
        jdbc.update("DELETE FROM payment_outbox WHERE aggregate_id = ?", payment.getId());
        jdbc.update("DELETE FROM stock_ledger WHERE payment_id = ? OR product_id = ?", payment.getId(), productId);
        paymentRepository.deleteById(payment.getId());
        orderRepository.deleteById(payment.getOrderId());
        productRepository.deleteById(productId);
        for (String email : emails) {
            var keys = redisTemplate.keys("*" + email + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            userRepository.findByEmail(email).ifPresent(u -> userRepository.deleteById(u.getId()));
        }
    }

    private UserJpaEntity seedUser(String prefix, Role role) {
        String email = prefix + "-" + marker + "@t.com";
        emails.add(email);
        return userRepository.save(UserJpaEntity.builder().email(email).password(passwordEncoder.encode("pw-1234"))
                .name(prefix).role(role).grade(Grade.BRONZE).build());
    }

    private String login(UserJpaEntity user) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"pw-1234\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static TossPaymentResponse tossResponse(String paymentKey) {
        try {
            return JSON.readValue("{\"paymentKey\":\"" + paymentKey + "\",\"totalAmount\":" + PRICE
                    + ",\"status\":\"DONE\",\"requestedAt\":\"2026-11-09T10:00:00+09:00\"}", TossPaymentResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String confirmBody(String key) {
        return "{\"paymentKey\":\"" + key + "\",\"orderId\":\"" + payment.getOrderId() + "\",\"amount\":" + PRICE + "}";
    }

    private String statusOfPayment() {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, payment.getId());
    }

    @Test
    @DisplayName("결제 ID 조회는 주인에게 200 이고, 남에게는 403 ACCESS_DENIED 에 결제 필드가 없다")
    void getPayment_남의_결제는_403이고_주인은_200이다() throws Exception {
        mockMvc.perform(get("/api/v1/payment/{id}", payment.getId()).contextPath("/api")
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(payment.getId().toString()));

        MvcResult denied = mockMvc.perform(get("/api/v1/payment/{id}", payment.getId()).contextPath("/api")
                        .header("Authorization", otherToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED")).andReturn();
        assertThat(denied.getResponse().getContentAsString()).doesNotContain("own-order-" + marker);
    }

    @Test
    @DisplayName("주문 ID 결제 조회는 주인에게 200 이고, 남에게는 403 ACCESS_DENIED 에 결제 필드가 없다")
    void getPaymentByOrder_남의_결제는_403이고_주인은_200이다() throws Exception {
        mockMvc.perform(get("/api/v1/payment/order/{id}", payment.getOrderId()).contextPath("/api")
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(payment.getId().toString()));

        MvcResult denied = mockMvc.perform(get("/api/v1/payment/order/{id}", payment.getOrderId()).contextPath("/api")
                        .header("Authorization", otherToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED")).andReturn();
        assertThat(denied.getResponse().getContentAsString()).doesNotContain("own-order-" + marker);
    }

    @Test
    @DisplayName("남의 orderId 로 승인하면 Toss 를 부르기 전에 403 이고 결제 상태는 그대로다")
    void confirm_남의_주문은_Toss호출없이_403이고_상태가_그대로다() throws Exception {
        mockMvc.perform(post("/api/v1/payment/confirm").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", otherToken).content(confirmBody(paymentKey)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verify(tossPaymentClient, never()).confirmPayment(any(), anyString());
        assertThat(statusOfPayment()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("이미 승인된 남의 결제의 paymentKey 로 승인해도 403 이고 결제 내용을 돌려주지 않는다")
    void confirm_이미_승인된_남의_결제_paymentKey도_403이다() throws Exception {
        jdbc.update("UPDATE payment SET status = 'DONE', payment_key = ? WHERE id = ?", paymentKey, payment.getId());

        MvcResult denied = mockMvc.perform(post("/api/v1/payment/confirm").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).header("Authorization", otherToken)
                        .content(confirmBody(paymentKey)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED")).andReturn();

        assertThat(denied.getResponse().getContentAsString()).doesNotContain(paymentKey);
        verify(tossPaymentClient, never()).confirmPayment(any(), anyString());
    }

    @Test
    @DisplayName("주인의 승인은 예전처럼 Toss 를 한 번 부르고 성공 응답(200 또는 202)을 받는다")
    void confirm_주인의_승인은_예전처럼_동작한다() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payment/confirm").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).header("Authorization", ownerToken)
                        .content(confirmBody(paymentKey)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isIn(200, 202);
        verify(tossPaymentClient, times(1)).confirmPayment(any(), anyString());
    }

    @Test
    @DisplayName("내 결제 목록은 내 결제만 담고, 결제 취소는 여전히 관리자 전용이다")
    void myPayments와_cancel_예전처럼_동작한다() throws Exception {
        mockMvc.perform(get("/api/v1/payment/my").contextPath("/api").header("Authorization", ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(payment.getId().toString()));
        mockMvc.perform(get("/api/v1/payment/my").contextPath("/api").header("Authorization", otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(post("/api/v1/payment/cancel").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"paymentId\":\"" + payment.getId() + "\",\"cancelReason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("호출자 사용자 id 가 null 이면 검사를 건너뛰지 않고 Toss 호출 전에 ACCESS_DENIED 로 거절한다")
    void confirm_호출자_id가_null이면_Toss호출없이_거절한다() {
        assertThatThrownBy(() -> paymentApplicationService.confirmPayment(null, paymentKey, payment.getOrderId(), PRICE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));

        verify(tossPaymentClient, never()).confirmPayment(any(), anyString());
        assertThat(statusOfPayment()).isEqualTo("PENDING");
    }
}
