package groom.backend.infrastructure.cache;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.product.ProductQueryController;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 상품 캐시 시험 세 클래스가 같은 스프링 컨텍스트를 쓰도록 설정을 모은다 - 이전 버전 삭제 유예는 200ms 로 줄인다(운영 5000ms)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CacheTestSupport.SqlCounting.class)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        "product.cache.old-version-grace-ms=200"
})
abstract class ProductCacheTestBase {

    @MockBean
    protected StockWarmUpRunner stockWarmUpRunner;
    @SpyBean
    protected StringRedisTemplate redis;

    @Autowired
    protected SpringDataProductRepository productRepository;
    @Autowired
    protected ProductStockRedisRepository stockRedisRepository;
    @Autowired
    protected ProductQueryController controller;
    @Autowired
    protected JdbcTemplate jdbc;

    protected MockMvc mockMvc;
    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void baseSetUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void baseTearDown() {
        org.mockito.Mockito.reset(redis);
        for (UUID id : created) {
            productRepository.deleteById(id);
            for (String key : redis.keys("*" + id + "*")) {
                redis.delete(key);
            }
            redis.delete("nf:product:" + id);
        }
        created.clear();
    }

    protected UUID save(String name, int price, int stock, boolean flash) {
        UUID id = productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name(name).description("설명")
                .price(price).stock(stock).isActive(true).category("GENERAL").status("AVAILABLE").flashSale(flash).build()).getId();
        created.add(id);
        if (flash) {
            stockRedisRepository.initStock(id, stock);
        }
        return id;
    }

    protected void track(UUID id) {
        created.add(id);
    }

    protected String pointer(UUID id) {
        return redis.opsForValue().get("product:" + id + ":ver");
    }

    protected String valueKeyBody(UUID id) {
        String ptr = pointer(id);
        return ptr == null ? null : redis.opsForValue().get("product:" + id + ":v" + ptr);
    }
}
