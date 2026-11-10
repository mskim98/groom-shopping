package groom.backend.interfaces.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import groom.backend.common.exception.GlobalExceptionHandler;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.product.persistence.ProductListTestSupport;
import groom.backend.interfaces.product.persistence.RecordingStatementInspector;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 목록 · 검색이 세는 행은 product.list.count-cap(시험에서 50)까지다 - 상한이 있으면 totalElements 는 min(실제 수, 50)이고,
// 첫 쪽이 차지 않으면 count 문을 아예 내지 않는다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        ProductListTestSupport.CAP_PROPERTY,
        ProductListTestSupport.INSPECTOR_PROPERTY
})
@DisplayName("상품 목록 · 검색의 개수 상한")
class ProductListCountCapTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private ProductQueryController controller;
    @Autowired
    private SpringDataProductRepository productRepository;

    private MockMvc mockMvc;
    private int price;
    private List<UUID> ids;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        price = ProductListTestSupport.uniquePrice();
    }

    @AfterEach
    void tearDown() {
        if (ids != null) {
            productRepository.deleteAllById(ids);
        }
    }

    private void saveProducts(int n) {
        ids = ProductListTestSupport.save(productRepository,
                ProductListTestSupport.numberedNames("cap-" + UUID.randomUUID(), n), price, "GENERAL");
    }

    @Test
    @DisplayName("검색_일치하는_행이_상한보다_많으면_totalElements 는 상한이고_쪽 수도 그에 맞다")
    void search_moreMatchesThanCap_totalIsCap() throws Exception {
        saveProducts(60);

        mockMvc.perform(get("/v1/product/search")
                        .param("minPrice", String.valueOf(price)).param("maxPrice", String.valueOf(price))
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(50))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content.length()").value(20));
    }

    @Test
    @DisplayName("목록_표의_행이_상한보다_많으면_totalElements 는 상한이다")
    void list_moreRowsThanCap_totalIsCap() throws Exception {
        saveProducts(60);

        mockMvc.perform(get("/v1/product").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(50))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    @DisplayName("검색_일치하는_행이_5개이고_쪽 크기가 20이면_count 문 없이 totalElements 5 를 답한다")
    void search_fewerThanPageSize_runsNoCountStatement() throws Exception {
        saveProducts(5);
        RecordingStatementInspector.clear();

        mockMvc.perform(get("/v1/product/search")
                        .param("minPrice", String.valueOf(price)).param("maxPrice", String.valueOf(price))
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(1));

        List<String> selects = RecordingStatementInspector.productSelects();
        assertThat(selects).hasSize(1);
        assertThat(selects.get(0).toLowerCase()).doesNotContain("count(");
    }

    @Test
    @DisplayName("목록_검색_count 문에는_상한이_들어_있다(표 전체를 세지 않는다)")
    void countStatement_carriesTheCap() throws Exception {
        saveProducts(60);

        for (String path : List.of("/v1/product", "/v1/product/search")) {
            RecordingStatementInspector.clear();
            var request = get(path).param("size", "20");
            if (path.endsWith("search")) {
                request.param("minPrice", String.valueOf(price)).param("maxPrice", String.valueOf(price));
            }
            mockMvc.perform(request).andExpect(status().isOk());

            List<String> counts = RecordingStatementInspector.productSelects().stream()
                    .filter(s -> s.toLowerCase().contains("count(")).toList();
            assertThat(counts).as(path + " count statements").hasSize(1);
            assertThat(counts.get(0).toLowerCase()).as(path + " count statement")
                    .containsAnyOf("limit ", "fetch first");
        }
    }
}
