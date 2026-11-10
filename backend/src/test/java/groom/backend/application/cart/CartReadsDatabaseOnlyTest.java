package groom.backend.application.cart;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.cart.CartApplicationService.CartItemToRemove;
import groom.backend.application.cart.CartApplicationService.CartViewResult;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 장바구니 저장소는 DB 하나다 - Redis 에 cart:<userId> 키가 남아 있어도 조회는 cart_item 행만 보여 준다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("장바구니는 DB 만 읽는다")
class CartReadsDatabaseOnlyTest {

    private static final long USER = 1L;

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner; // 1M 시드 워밍업 무력화

    @Autowired
    private CartApplicationService cartApplicationService;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    @Qualifier("redisTemplate")
    private RedisTemplate<String, String> redisTemplate;

    private UUID p1;
    private UUID p2;
    private UUID ghost; // DB 장바구니에는 없고 Redis 키에만 있는 상품
    private final String redisKey = "cart:" + USER;

    @BeforeEach
    void setUp() {
        p1 = newProduct();
        p2 = newProduct();
        ghost = newProduct();
        clearCart();
        // 옛 Redis 장바구니가 남긴 키: DB 에 없는 상품 하나와 DB 상품의 어긋난 수량
        redisTemplate.opsForHash().put(redisKey, ghost.toString(), cartJson(ghost, 5));
        redisTemplate.opsForHash().put(redisKey, p1.toString(), cartJson(p1, 9));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(redisKey);
        clearCart();
        productRepository.deleteAllById(List.of(p1, p2, ghost));
    }

    @Test
    @DisplayName("Redis 키에 DB 장바구니에 없는 상품이 있어도 조회는 DB 항목만 보여 주고 수량도 DB 값이다")
    void 조회_Redis_키의_상품은_보이지_않고_DB_행만_보인다() {
        cartApplicationService.addToCart(USER, p1, 2);

        CartViewResult view = cartApplicationService.getCartItems(USER);

        assertThat(viewQuantities(view)).containsExactlyEntriesOf(Map.of(p1, 2));
        assertViewEqualsRows();
    }

    @Test
    @DisplayName("담기 · 수량 증가 · 수량 감소 · 제거를 부를 때마다 조회가 cart_item 행과 같다")
    void 변경_호출마다_조회가_cart_item_행과_같다() {
        cartApplicationService.addToCart(USER, p1, 1);
        assertViewEqualsRows();
        assertThat(rows()).containsExactlyEntriesOf(Map.of(p1, 1));

        cartApplicationService.addToCart(USER, p2, 2);
        assertViewEqualsRows();

        cartApplicationService.increaseQuantity(USER, p1);
        assertViewEqualsRows();
        assertThat(rows()).containsEntry(p1, 2);

        cartApplicationService.decreaseQuantity(USER, p1);
        assertViewEqualsRows();
        assertThat(rows()).containsEntry(p1, 1);

        cartApplicationService.removeCartItems(USER, List.of(new CartItemToRemove(p2, 1)));
        assertViewEqualsRows();
        assertThat(rows()).containsEntry(p2, 1);

        cartApplicationService.removeCartItems(USER, List.of(new CartItemToRemove(p2, 1)));
        assertViewEqualsRows();
        assertThat(rows()).as("수량이 0 이 된 줄은 사라진다").containsExactlyEntriesOf(Map.of(p1, 1));
    }

    private void assertViewEqualsRows() {
        CartViewResult view = cartApplicationService.getCartItems(USER);
        assertThat(viewQuantities(view)).as("조회 항목").isEqualTo(rows());
        assertThat(view.getTotalItems()).as("totalItems").isEqualTo(rows().size());
    }

    private Map<UUID, Integer> viewQuantities(CartViewResult view) {
        Map<UUID, Integer> quantities = new HashMap<>();
        view.getItems().forEach(item -> quantities.put(item.getProductId(), item.getQuantity()));
        return quantities;
    }

    private Map<UUID, Integer> rows() {
        Map<UUID, Integer> rows = new HashMap<>();
        jdbc.query("SELECT ci.product_id, ci.quantity FROM cart_item ci JOIN cart c ON c.id = ci.cart_id WHERE c.user_id = ?",
                rs -> {
                    rows.put(rs.getObject("product_id", UUID.class), rs.getInt("quantity"));
                }, USER);
        return rows;
    }

    private void clearCart() {
        jdbc.update("DELETE FROM cart_item WHERE cart_id IN (SELECT id FROM cart WHERE user_id = ?)", USER);
    }

    private UUID newProduct() {
        return productRepository.save(ProductJpaEntity.builder()
                .id(UUID.randomUUID()).name("cart-db-only-product").price(1000).stock(10)
                .isActive(true).category("GENERAL").status("AVAILABLE").build()).getId();
    }

    private static String cartJson(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + ",\"cartItemId\":null}";
    }
}
