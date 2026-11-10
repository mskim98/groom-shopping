package groom.backend.interfaces.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import groom.backend.common.exception.GlobalExceptionHandler;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.product.persistence.ProductListTestSupport;
import groom.backend.interfaces.product.persistence.RecordingStatementInspector;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 프런트의 목록 정렬(category 오름차순, id 내림차순, 크기 20)은 인덱스가 정렬된 채로 내주므로 계획에 Sort 노드가 없다
// 첫 쪽(page 0)과 상한 50 이 허용하는 마지막 쪽(page 2, 시작 위치 40)을 저장소가 낸 SQL 그대로 EXPLAIN 한다
// 정렬(Sort · Incremental Sort)도 끄고 본다 - 작은 시험 표에서는 앞서 돈 시험이 남긴 통계에 따라 플래너가 idx_product_category 위 Incremental Sort 를 골라
// 전체 시험에서만 실패했다. 정렬을 꺼도 정렬 없이 읽는 인덱스가 없으면 Sort 가 남으므로 이 단언은 인덱스가 있는지를 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        ProductListTestSupport.CAP_PROPERTY,
        ProductListTestSupport.INSPECTOR_PROPERTY
})
@DisplayName("상품 목록 정렬 인덱스")
class ProductListSortIndexTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private ProductQueryController controller;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("EXPLAIN_첫 쪽_category asc, id desc 목록은_Sort 노드가 없다")
    void explain_firstPage_hasNoSortNode() throws Exception {
        assertThat(planOfListPage(0)).doesNotContain("Sort");
    }

    @Test
    @DisplayName("EXPLAIN_상한이 허용하는 마지막 쪽_category asc, id desc 목록은_Sort 노드가 없다")
    void explain_lastPageWithinCap_hasNoSortNode() throws Exception {
        assertThat(planOfListPage(2)).doesNotContain("Sort");
    }

    private String planOfListPage(int page) throws Exception {
        RecordingStatementInspector.clear();
        mockMvc.perform(get("/v1/product").param("page", String.valueOf(page)).param("size", "20")
                        .param("sort", "category,asc").param("sort", "id,desc"))
                .andExpect(status().isOk());
        List<String> selects = RecordingStatementInspector.productListSelects();
        assertThat(selects).hasSize(1);
        String sql = selects.get(0);

        // ? 의 순서: 시작 위치 · 쪽 크기
        return ProductListTestSupport.explainWithoutSeqScan(jdbc, sql, List.of(page * 20, 20),
                "enable_sort", "enable_incremental_sort");
    }
}
