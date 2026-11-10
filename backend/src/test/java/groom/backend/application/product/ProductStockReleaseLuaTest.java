package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;

import groom.backend.infrastructure.product.StockWarmUpRunner;

// 복원 스크립트가 없는 키를 만들지 않는지 본다 - 만들어진 키는 "1개 남음" 으로 읽혀 유실을 가린다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("Redis 재고 복원 Lua")
class ProductStockReleaseLuaTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private ProductStockRedisRepository repository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private final UUID productId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId);
    }

    @Test
    @DisplayName("release 는 없는 키를 없는 채로 둔다")
    void release_없는_키는_없는_채로_둔다() {
        repository.release(productId, 3);

        assertThat(redisTemplate.hasKey(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId)).isFalse();
    }

    @Test
    @DisplayName("release 는 있는 키에 수량을 더한다")
    void release_있는_키에_수량을_더한다() {
        repository.initStock(productId, 5);

        repository.release(productId, 3);

        assertThat(redisTemplate.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + productId))
                .isEqualTo("8");
    }
}
