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
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// 이름 검색은 "포함" 의미를 지키고, 2글자 키워드는 V25 가 만든 2-gram 인덱스가 받는다(3글자는 trigram 인덱스)
// EXPLAIN 은 저장소가 실제로 낸 SQL(Hibernate 에서 가로챔)을 enable_seqscan=off 로 돌려 본다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        ProductListTestSupport.CAP_PROPERTY,
        ProductListTestSupport.INSPECTOR_PROPERTY
})
@DisplayName("상품 이름 검색과 2글자 인덱스")
class ProductNameSearchIndexTest {

    private static final String GRAM_INDEX = "idx_product_name_grams2";

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private ProductQueryController controller;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private int price;
    private List<UUID> ids;
    private String filler;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        price = ProductListTestSupport.uniquePrice();
        ids = ProductListTestSupport.save(productRepository, List.of("편한의자쿠션", "의자", "Hub Stand"), price, "GENERAL");
    }

    @AfterEach
    void tearDown() {
        productRepository.deleteAllById(ids);
        if (filler != null) {
            ProductListTestSupport.dropFiller(jdbc, filler);
        }
    }

    private org.springframework.test.web.servlet.ResultActions search(String keyword) throws Exception {
        return mockMvc.perform(get("/v1/product/search").param("name", keyword)
                .param("minPrice", String.valueOf(price)).param("maxPrice", String.valueOf(price)).param("size", "20"));
    }

    @Test
    @DisplayName("검색_의자_이름에 의자가 들어간 두 상품을 모두 답한다")
    void search_twoCharKeyword_returnsEveryNameContainingIt() throws Exception {
        search("의자").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].name").value(org.hamcrest.Matchers.containsInAnyOrder("편한의자쿠션", "의자")));
    }

    @Test
    @DisplayName("검색_자쿠_이름 가운데에 들어간 상품 하나만 답한다")
    void search_keywordInTheMiddle_returnsOnlyThatProduct() throws Exception {
        search("자쿠").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("편한의자쿠션"));
    }

    @Test
    @DisplayName("검색_ub_대소문자를 가리지 않고 Hub Stand 하나만 답한다")
    void search_lowercaseKeywordOfMixedCaseName_returnsIt() throws Exception {
        search("ub").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Hub Stand"));
    }

    @Test
    @DisplayName("EXPLAIN_2글자 키워드_V25 의 2-gram 인덱스를 쓰고_product 표 순차 읽기가 없다")
    void explain_twoCharKeyword_usesGramIndex() throws Exception {
        String plan = explainOfSearch("ub");

        assertThat(plan).contains(GRAM_INDEX);
        assertThat(plan).doesNotContain("Seq Scan on product");
    }

    @Test
    @DisplayName("EXPLAIN_3글자 키워드_product 표 순차 읽기가 없다")
    void explain_threeCharKeyword_hasNoSeqScan() throws Exception {
        String plan = explainOfSearch("의자쿠");

        assertThat(plan).doesNotContain("Seq Scan on product");
    }

    /** 검색 한 번이 낸 목록 SELECT 를 가로채 그대로 EXPLAIN 한다 */
    private String explainOfSearch(String keyword) throws Exception {
        filler = "zzfill-" + UUID.randomUUID();
        ProductListTestSupport.fillProductTable(jdbc, filler, 5000);
        RecordingStatementInspector.clear();
        search(keyword).andExpect(status().isOk());
        List<String> selects = RecordingStatementInspector.productListSelects();
        assertThat(selects).hasSize(1);
        String sql = selects.get(0);

        // ? 의 순서: 이름 like · (2글자면 2-gram 키워드) · 최소 가격 · 최대 가격 · 시작 위치 · 쪽 크기
        List<Object> values = new ArrayList<>(List.of("%" + keyword.toLowerCase() + "%"));
        if (keyword.length() == 2 && sql.chars().filter(c -> c == '?').count() == 6) {
            values.add(keyword.toLowerCase());
        }
        values.addAll(List.of(price, price, 0, 20));
        return ProductListTestSupport.explainWithoutSeqScan(jdbc, sql, values);
    }
}
