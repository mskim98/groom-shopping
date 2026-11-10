package groom.backend.interfaces.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import groom.backend.common.exception.GlobalExceptionHandler;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.product.persistence.ProductListTestSupport;
import groom.backend.interfaces.product.persistence.RecordingStatementInspector;
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

// 시작 위치(page * size)가 상한(시험에서 50) 이상인 쪽은 DB 를 부르기 전에 400 으로 막는다
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false",
        ProductListTestSupport.CAP_PROPERTY,
        ProductListTestSupport.INSPECTOR_PROPERTY
})
@DisplayName("상품 목록 · 검색의 깊이 제한")
class ProductListDepthLimitTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private ProductQueryController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("목록_시작 위치 60 이 상한 50 을 넘으면_400 과 오류 본문을 답하고_product 문을 내지 않는다")
    void list_offsetBeyondCap_is400AndRunsNoStatement() throws Exception {
        RecordingStatementInspector.clear();

        mockMvc.perform(get("/v1/product").param("page", "3").param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("PRODUCT_LIST_PAGE_TOO_DEEP"));

        assertThat(RecordingStatementInspector.productSelects()).isEmpty();
    }

    @Test
    @DisplayName("검색_시작 위치 60 이 상한 50 을 넘으면_400 과 오류 본문을 답하고_product 문을 내지 않는다")
    void search_offsetBeyondCap_is400AndRunsNoStatement() throws Exception {
        RecordingStatementInspector.clear();

        mockMvc.perform(get("/v1/product/search").param("name", "ab").param("page", "3").param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_LIST_PAGE_TOO_DEEP"));

        assertThat(RecordingStatementInspector.productSelects()).isEmpty();
    }

    @Test
    @DisplayName("시작 위치가_상한과_같으면(page 2, size 25) 막고_상한 바로 아래(page 1, size 49)면 통과한다")
    void offsetEqualToCap_isRefused_justBelowPasses() throws Exception {
        for (String path : new String[] {"/v1/product", "/v1/product/search"}) {
            mockMvc.perform(get(path).param("page", "2").param("size", "25")).andExpect(status().isBadRequest());
            mockMvc.perform(get(path).param("page", "1").param("size", "49")).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("시작 위치 40 은_상한 50 안이라_목록과 검색 모두 200 이다")
    void offsetWithinCap_is200() throws Exception {
        mockMvc.perform(get("/v1/product").param("page", "2").param("size", "20")).andExpect(status().isOk());
        mockMvc.perform(get("/v1/product/search").param("name", "ab").param("page", "2").param("size", "20"))
                .andExpect(status().isOk());
    }
}
