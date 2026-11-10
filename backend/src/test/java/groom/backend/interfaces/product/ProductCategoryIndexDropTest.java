package groom.backend.interfaces.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// V1 의 idx_product_category(category)는 V25 의 idx_product_category_id_desc(category, id DESC)가 같은 선행 열로 덮는다
// r119 전 구간에서 플래너가 쓸모가 더 좁은 V1 인덱스를 골라 Incremental Sort 로 정렬했다 - V26 이 V1 인덱스를 지운다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("상품 category 인덱스 정리(V26)")
class ProductCategoryIndexDropTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("pg_indexes_V26 뒤_V1 의 category 단독 인덱스가 없다")
    void pgIndexes_afterV26_noCategoryOnlyIndex() {
        assertThat(productIndexNames()).doesNotContain("idx_product_category");
    }

    @Test
    @DisplayName("pg_indexes_V26 뒤_V25 의 category·id 내림차순 인덱스는 남아 있다")
    void pgIndexes_afterV26_keepsCategoryIdDescIndex() {
        assertThat(productIndexNames()).contains("idx_product_category_id_desc");
    }

    @Test
    @DisplayName("pg_indexes_V26 뒤_V25 의 검색 인덱스와 기본키는 그대로다")
    void pgIndexes_afterV26_keepsOtherIndexes() {
        assertThat(productIndexNames()).contains(
                "idx_product_name_grams2", "idx_product_name_trgm", "product_pkey");
    }

    private List<String> productIndexNames() {
        return jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'product'",
                String.class);
    }
}
