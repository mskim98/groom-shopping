package groom.backend.interfaces.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 플래시 세일 상품의 관리자 재고 증감은 409 로 막고 stock · Redis 키를 바꾸지 않는지, 일반 상품은 그대로 동작하는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("플래시 세일 상품의 관리자 재고 변경")
class FlashSaleAdminStockChangeTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private ProductCommonController controller;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private ProductStockRedisRepository stockRedisRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private UUID flashId;
    private UUID plainId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        flashId = save(10, true);
        plainId = save(10, false);
        stockRedisRepository.initStock(flashId, 10);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + flashId);
        productRepository.deleteById(flashId);
        productRepository.deleteById(plainId);
    }

    private UUID save(int stock, boolean flash) {
        return productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name("admin-stock").price(1000)
                .stock(stock).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(flash).build()).getId();
    }

    private int stock(UUID id) {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("재고 증가 핸들러는 플래시 상품에 409 로 답하고 stock · Redis 키를 바꾸지 않는다")
    void increaseStock_플래시상품_409이고_재고와_Redis키를_바꾸지_않는다() throws Exception {
        mockMvc.perform(patch("/v1/product/{id}/stock/increase", flashId).param("amount", "5"))
                .andExpect(status().isConflict());

        assertThat(stock(flashId)).isEqualTo(10);
        assertThat(redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + flashId)).isEqualTo("10");
    }

    @Test
    @DisplayName("재고 감소 핸들러는 플래시 상품에 409 로 답하고 stock · Redis 키를 바꾸지 않는다")
    void decreaseStock_플래시상품_409이고_재고와_Redis키를_바꾸지_않는다() throws Exception {
        mockMvc.perform(patch("/v1/product/{id}/stock/decrease", flashId).param("amount", "5"))
                .andExpect(status().isConflict());

        assertThat(stock(flashId)).isEqualTo(10);
        assertThat(redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + flashId)).isEqualTo("10");
    }

    @Test
    @DisplayName("상품 수정이 재고 값을 바꾸는 요청이면 플래시 상품에 409 이고, 다른 필드만 바꾸는 요청은 통과한다")
    void updateProduct_플래시상품_재고를_바꾸면_409이고_다른_필드는_통과한다() throws Exception {
        mockMvc.perform(patch("/v1/product/{id}", flashId).contentType("application/json").content("{\"stock\":50}"))
                .andExpect(status().isConflict());
        assertThat(stock(flashId)).isEqualTo(10);

        mockMvc.perform(patch("/v1/product/{id}", flashId).contentType("application/json").content("{\"price\":2000}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT flash_sale FROM product WHERE id = ?", Boolean.class, flashId))
                .as("수정 저장이 플래시 표시를 지우지 않는다").isTrue();
    }

    @Test
    @DisplayName("일반 상품은 재고 증가 · 감소가 예전처럼 동작한다")
    void stock변경_일반상품_예전처럼_동작한다() throws Exception {
        mockMvc.perform(patch("/v1/product/{id}/stock/increase", plainId).param("amount", "5"))
                .andExpect(status().isOk());
        assertThat(stock(plainId)).isEqualTo(15);

        mockMvc.perform(patch("/v1/product/{id}/stock/decrease", plainId).param("amount", "3"))
                .andExpect(status().isOk());
        assertThat(stock(plainId)).isEqualTo(12);
    }
}
