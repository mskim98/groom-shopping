package groom.backend.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.service.ProductCommonService;
import groom.backend.interfaces.product.dto.request.CreateProductRequest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

// 없는 상품 id 는 nf:product:{id}(5초)에 적어 두 번째 읽기가 SQL 없이 404 로 끝나고, 만들면 키가 지워지고, 지우면 404 인지 본다
@DisplayName("상품 부재 캐시")
class ProductNegativeCacheTest extends ProductCacheTestBase {

    @Autowired
    private ProductCommonService productCommonService;
    @Autowired
    private ProductDetailCache cache;

    @Test
    @DisplayName("없는 id 는 404 이고 nf 키를 5초 만료로 쓰며 두 번째 읽기는 SQL 0 으로 404 다")
    void findById_없는_id_404이고_nf_키가_SQL을_막는다() throws Exception {
        UUID id = UUID.randomUUID();
        track(id);

        mockMvc.perform(get("/v1/product/{id}", id)).andExpect(status().isNotFound());

        assertThat(redis.hasKey("nf:product:" + id)).isTrue();
        assertThat(redis.getExpire("nf:product:" + id, TimeUnit.MILLISECONDS)).isBetween(1L, 5000L);
        CacheTestSupport.SQL.clear();

        mockMvc.perform(get("/v1/product/{id}", id)).andExpect(status().isNotFound());

        assertThat(CacheTestSupport.SQL).as("두 번째 읽기 SQL").isEmpty();
    }

    @Test
    @DisplayName("createProduct 뒤 nf 키가 없고 만든 id 를 읽으면 200 이다")
    void createProduct_뒤_nf_키가_없고_읽으면_200이다() throws Exception {
        Product created = productCommonService.createProduct(
                new CreateProductRequest("negative-create", "설명", 1000, 3, ProductCategory.GENERAL));
        track(created.getId());

        assertThat(redis.hasKey("nf:product:" + created.getId())).as("nf 키").isFalse();
        mockMvc.perform(get("/v1/product/{id}", created.getId())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("L1 본문이 남은 인스턴스에서 수량 항목이 없고 행이 DB 에서 직접 지워졌으면 예외가 아니라 404 이고 본문 항목이 버려진다")
    void findById_L1_본문이_남고_행이_지워졌으면_404다() throws Exception {
        UUID id = save("negative-gone", 1000, 3, false);
        mockMvc.perform(get("/v1/product/{id}", id)).andExpect(status().isOk());
        // 다른 인스턴스가 지운 상황 - 이 인스턴스의 deleteProduct 를 거치지 않고 행만 지운다
        jdbc.update("DELETE FROM product WHERE id = ?", id);
        // 본문 L1 은 남기고 수량 L1 만 비운다(운영에서는 두 항목의 쓰기 시각이 달라 만료 시점이 어긋날 수 있다)
        @SuppressWarnings("unchecked")
        Cache<UUID, Integer> quantities = (Cache<UUID, Integer>) ReflectionTestUtils.getField(cache, "quantities");
        quantities.invalidate(id);

        mockMvc.perform(get("/v1/product/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deleteProduct 뒤 읽기는 404 다")
    void deleteProduct_뒤_읽기는_404다() throws Exception {
        UUID id = save("negative-delete", 1000, 3, false);
        mockMvc.perform(get("/v1/product/{id}", id)).andExpect(status().isOk());

        productCommonService.deleteProduct(id);

        mockMvc.perform(get("/v1/product/{id}", id)).andExpect(status().isNotFound());
    }
}
