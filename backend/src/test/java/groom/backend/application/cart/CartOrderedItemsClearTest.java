package groom.backend.application.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.order.model.enums.OrderStatus;
import groom.backend.domain.order.repository.OrderRepository;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.infrastructure.security.JwtRsaTokenProvider;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// 주문한 항목을 장바구니에서 빼는 DELETE /v1/cart/ordered-items/{orderId} 를 실제 보안 필터 체인으로 본다
// 주문 하나에 한 번만 빼고(PostgreSQL 표시), 남의 주문과 확정되지 않은 주문은 장바구니도 표시도 건드리지 않는다
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("주문한 항목 장바구니에서 빼기")
class CartOrderedItemsClearTest {

    private static final int PRICE = 1000;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CartApplicationService cartService;
    @Autowired
    private JwtRsaTokenProvider tokenProvider;
    @Autowired
    private JdbcTemplate jdbc;

    private UserJpaEntity owner;
    private UserJpaEntity other;
    private UUID firstProduct;
    private UUID secondProduct;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        owner = newUser("owner");
        other = newUser("other");
        firstProduct = newProduct();
        secondProduct = newProduct();
        // 장바구니: 첫째 상품 5개, 둘째 상품 2개
        cartService.addToCart(owner.getId(), firstProduct, 5);
        cartService.addToCart(owner.getId(), secondProduct, 2);
        // 주문: 첫째 상품 3개, 둘째 상품 2개 - 확정 뒤 첫째는 2개가 남고 둘째 줄은 사라져야 한다
        orderId = newOrder(owner.getId(), OrderStatus.CONFIRMED);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM cart_ordered_items_cleared WHERE order_id = ?", orderId);
        for (UserJpaEntity user : List.of(owner, other)) {
            jdbc.update("DELETE FROM cart_item WHERE cart_id IN (SELECT id FROM cart WHERE user_id = ?)", user.getId());
            jdbc.update("DELETE FROM cart WHERE user_id = ?", user.getId());
        }
        orderRepository.deleteById(orderId);
        productRepository.deleteById(firstProduct);
        productRepository.deleteById(secondProduct);
        userRepository.deleteById(owner.getId());
        userRepository.deleteById(other.getId());
    }

    private UserJpaEntity newUser(String prefix) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(UserJpaEntity.builder().email(prefix + "-" + unique + "@t.com").password("x")
                .name(prefix).role(Role.ROLE_USER).grade(Grade.BRONZE).build());
    }

    private UUID newProduct() {
        return productRepository.save(ProductJpaEntity.builder()
                .id(UUID.randomUUID()).name("clear-product").price(PRICE).stock(100)
                .isActive(true).category("GENERAL").status("AVAILABLE").build()).getId();
    }

    private UUID newOrder(Long userId, OrderStatus status) {
        Order order = Order.builder().userId(userId).couponId(null).status(status).build();
        order.addOrderItem(OrderItem.builder().productId(firstProduct).productName("clear-product")
                .price(PRICE).quantity(3).build());
        order.addOrderItem(OrderItem.builder().productId(secondProduct).productName("clear-product")
                .price(PRICE).quantity(2).build());
        order.calculateAmounts();
        return orderRepository.save(order).getId();
    }

    private int cartQuantity(UUID productId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT ci.quantity FROM cart_item ci JOIN cart c ON c.id = ci.cart_id WHERE c.user_id = ? AND ci.product_id = ?",
                Integer.class, owner.getId(), productId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private int marks(UUID order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_ordered_items_cleared WHERE order_id = ?", Integer.class, order);
    }

    private int callAs(UserJpaEntity user, UUID order) throws Exception {
        String token = tokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        return mockMvc.perform(delete("/api/v1/cart/ordered-items/" + order).contextPath("/api")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("확정된 주문의 주인이 부르면 주문 수량만큼 빼고(0 이하가 된 줄은 지운다) 204 를 돌려주며 표시를 남긴다")
    void 비우기_주인이_확정주문으로_부르면_수량을_빼고_204이며_표시가_남는다() throws Exception {
        assertThat(callAs(owner, orderId)).isEqualTo(204);

        assertThat(cartQuantity(firstProduct)).as("첫째 상품: 5 - 3").isEqualTo(2);
        assertThat(cartQuantity(secondProduct)).as("둘째 상품: 2 - 2 = 0 이라 줄이 사라진다").isZero();
        assertThat(marks(orderId)).as("PostgreSQL 에서 읽은 주문별 표시 수").isEqualTo(1);
    }

    @Test
    @DisplayName("두 번째 호출도 204 이고 장바구니는 첫 호출이 남긴 그대로다")
    void 비우기_두번째_호출도_204이고_장바구니는_그대로다() throws Exception {
        assertThat(callAs(owner, orderId)).isEqualTo(204);
        // 첫 호출 뒤 사용자가 다시 담은 수량이 두 번째 호출에 줄어들면 안 된다
        cartService.addToCart(owner.getId(), firstProduct, 7);
        assertThat(cartQuantity(firstProduct)).isEqualTo(9);

        assertThat(callAs(owner, orderId)).isEqualTo(204);

        assertThat(cartQuantity(firstProduct)).as("두 번째 호출이 또 빼지 않는다").isEqualTo(9);
        assertThat(marks(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 주문에 두 호출이 동시에 와도 한 번만 빼고 둘 다 204 이다")
    void 비우기_같은_주문_동시_두호출은_한번만_빼고_둘다_204이다() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = List.of(
                CompletableFuture.supplyAsync(() -> concurrentCall(ready, go)),
                CompletableFuture.supplyAsync(() -> concurrentCall(ready, go)));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        assertThat(calls.get(0).get(15, TimeUnit.SECONDS)).isEqualTo(204);
        assertThat(calls.get(1).get(15, TimeUnit.SECONDS)).isEqualTo(204);
        assertThat(cartQuantity(firstProduct)).as("한 번만 뺀다: 5 - 3").isEqualTo(2);
        assertThat(cartQuantity(secondProduct)).isZero();
        assertThat(marks(orderId)).isEqualTo(1);
    }

    private int concurrentCall(CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return callAs(owner, orderId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("남의 주문이면 기존 주문 주인 확인의 상태(403)로 거절하고 장바구니도 표시도 그대로다")
    void 비우기_남의_주문이면_403이고_장바구니와_표시가_그대로다() throws Exception {
        assertThat(callAs(other, orderId)).isEqualTo(403);

        assertThat(cartQuantity(firstProduct)).isEqualTo(5);
        assertThat(cartQuantity(secondProduct)).isEqualTo(2);
        assertThat(marks(orderId)).as("거절된 호출은 표시를 남기지 않는다").isZero();
    }

    @Test
    @DisplayName("확정되지 않은 주문이면 409 로 거절하고 장바구니도 표시도 그대로다")
    void 비우기_확정되지_않은_주문이면_409이고_장바구니와_표시가_그대로다() throws Exception {
        UUID pendingOrder = newOrder(owner.getId(), OrderStatus.PENDING);
        try {
            assertThat(callAs(owner, pendingOrder)).isEqualTo(409);

            assertThat(cartQuantity(firstProduct)).isEqualTo(5);
            assertThat(cartQuantity(secondProduct)).isEqualTo(2);
            assertThat(marks(pendingOrder)).as("거절된 호출은 표시를 남기지 않는다").isZero();
        } finally {
            orderRepository.deleteById(pendingOrder);
        }
    }
}
