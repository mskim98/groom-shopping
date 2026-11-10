package groom.backend.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
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
import org.springframework.beans.factory.annotation.Autowired;

// 없는 상품 id 는 nf:product:{id}(5초)에 적어 두 번째 읽기가 SQL 없이 404 로 끝나고, 만들면 키가 지워지고, 지우면 404 인지 본다
@DisplayName("상품 부재 캐시")
class ProductNegativeCacheTest extends ProductCacheTestBase {

    @Autowired
    private ProductCommonService productCommonService;

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
    @DisplayName("createProduct 는 만든 id 의 nf 키를 지운다")
    void createProduct_만든_id의_nf_키를_지운다() {
        Product created = productCommonService.createProduct(
                new CreateProductRequest("negative-create", "설명", 1000, 3, ProductCategory.GENERAL));
        track(created.getId());

        verify(redis).delete("nf:product:" + created.getId());
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
