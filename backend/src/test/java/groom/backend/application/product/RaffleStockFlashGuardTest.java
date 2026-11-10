package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 추첨 경품 재고 차감(reduceStock)이 플래시 세일 상품의 재고를 Redis · 원장 밖에서 낮추지 못하는지, 다른 상품은 그대로 낮추는지 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("추첨 경품 재고 차감의 플래시 세일 보호")
class RaffleStockFlashGuardTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private ProductApplicationService productApplicationService;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID flashId;
    private UUID plainId;

    @BeforeEach
    void setUp() {
        flashId = save(10, true);
        plainId = save(10, false);
    }

    @AfterEach
    void tearDown() {
        productRepository.deleteById(flashId);
        productRepository.deleteById(plainId);
    }

    private UUID save(int stock, boolean flash) {
        return productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name("raffle-prize").price(1000)
                .stock(stock).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(flash).build()).getId();
    }

    private int stock(UUID id) {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("플래시 상품에 reduceStock 을 부르면 예외로 거절하고 재고를 바꾸지 않는다")
    void reduceStock_플래시상품_거절하고_재고는_그대로다() {
        assertThatThrownBy(() -> productApplicationService.reduceStock(flashId, 3))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(stock(flashId)).isEqualTo(10);
    }

    @Test
    @DisplayName("플래시가 아닌 상품에 reduceStock 을 부르면 재고를 낮추고 차감 후 재고를 돌려준다")
    void reduceStock_일반상품_재고를_낮춘다() {
        Integer after = productApplicationService.reduceStock(plainId, 3);

        assertThat(after).isEqualTo(7);
        assertThat(stock(plainId)).isEqualTo(7);
    }
}
