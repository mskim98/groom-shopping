package groom.backend.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

// 상품 상세가 L1(1초) → L2(포인터 + 버전 값) → DB 순으로 본문을 읽고, 수량은 본문과 따로 1초 캐시되는지 본다
@DisplayName("상품 상세 L1/L2 캐시")
class ProductDetailCacheTest extends ProductCacheTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode fetch(UUID id) throws Exception {
        return JSON.readTree(mockMvc.perform(get("/v1/product/{id}", id)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    @Test
    @DisplayName("1초 안의 두 번째 읽기는 SQL 0 · Redis 명령 0 이고 응답 필드 이름과 타입이 기존과 같다")
    void findById_L1_적중이면_SQL과_Redis를_쓰지_않는다() throws Exception {
        UUID id = save("detail-a", 1500, 7, false);
        JsonNode first = fetch(id);
        CacheTestSupport.SQL.clear();
        clearInvocations(redis);

        JsonNode second = fetch(id);

        assertThat(CacheTestSupport.SQL).as("두 번째 읽기 SQL").isEmpty();
        assertThat(mockingDetails(redis).getInvocations()).as("두 번째 읽기 Redis 호출").isEmpty();
        assertThat(second).isEqualTo(first);
        assertThat(second.get("productId").asText()).isEqualTo(id.toString());
        assertThat(second.get("name").asText()).isEqualTo("detail-a");
        assertThat(second.get("description").asText()).isEqualTo("설명");
        assertThat(second.get("price").isInt()).isTrue();
        assertThat(second.get("stock").asInt()).isEqualTo(7);
        assertThat(second.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(second.get("category").asText()).isEqualTo("GENERAL");
        assertThat(second.has("imageUrl")).isTrue();
    }

    @Test
    @DisplayName("L1 이 만료된 뒤의 읽기는 본문 SQL 0 이고 L2 값에는 재고와 상태가 없다")
    void findById_L1_만료뒤_본문SQL이_없고_L2에_재고가_없다() throws Exception {
        UUID id = save("detail-b", 1500, 7, false);
        fetch(id);
        String body = valueKeyBody(id);
        assertThat(body).as("L2 값").isNotNull().doesNotContain("stock").doesNotContain("status").contains("detail-b");
        Thread.sleep(1200);
        CacheTestSupport.SQL.clear();

        JsonNode again = fetch(id);

        assertThat(CacheTestSupport.bodySelects()).as("본문 SQL").isZero();
        assertThat(again.get("name").asText()).isEqualTo("detail-b");
    }

    @Test
    @DisplayName("플래시 선점 뒤 응답은 1.2초 안에 낮은 수량을 보이고 본문 키는 그대로다")
    void findById_플래시_선점뒤_낮은_수량을_보인다() throws Exception {
        UUID id = save("detail-c", 1500, 10, true);
        assertThat(fetch(id).get("stock").asInt()).isEqualTo(10);
        String bodyBefore = valueKeyBody(id);
        String pointerBefore = pointer(id);

        stockRedisRepository.preReserve(id, 1);

        await().atMost(Duration.ofMillis(1200)).pollInterval(Duration.ofMillis(100))
                .until(() -> fetch(id).get("stock").asInt() == 9);
        assertThat(valueKeyBody(id)).isEqualTo(bodyBefore);
        assertThat(pointer(id)).isEqualTo(pointerBefore);
    }

    @Test
    @DisplayName("Redis 읽기가 모두 예외를 던져도 응답은 200 이고 DB 값이다")
    void findById_Redis_읽기_실패_DB로_200이다() throws Exception {
        UUID id = save("detail-d", 1500, 7, false);
        doThrow(new RedisConnectionFailureException("down")).when(redis).opsForValue();

        mockMvc.perform(get("/v1/product/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("detail-d"))
                .andExpect(jsonPath("$.stock").value(7));
    }
}
