package groom.backend.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.domain.product.service.ProductCommonService;
import groom.backend.interfaces.product.dto.request.UpdateProductRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

// 상품 본문 변경은 커밋 뒤에 새 값 키 → 포인터 이동 → 옛 값 키 지연 삭제 순으로 버전을 바꾸는지, 재고만 바뀌면 포인터가 움직이지 않는지 본다
@DisplayName("상품 캐시 버전 전환")
class ProductCacheVersionSwitchTest extends ProductCacheTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ProductCommonService productCommonService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ProductDetailCache cache;

    private JsonNode read(UUID id) throws Exception {
        return JSON.readTree(mockMvc.perform(MockMvcRequestBuilders.get("/v1/product/{id}", id)).andReturn().getResponse()
                .getContentAsString());
    }

    private void update(UUID id, int k) {
        productCommonService.updateProduct(id, new UpdateProductRequest("name-" + k, null, 1000 + k, null, null));
    }

    @Test
    @DisplayName("읽기 1000건이 도는 동안 이름과 가격을 함께 바꾸는 갱신 20건이 있어도 한 갱신의 이름에 다른 갱신의 가격이 붙은 응답이 없다")
    void updateProduct_읽기와_갱신이_겹쳐도_이름과_가격이_짝이_맞는다() throws Exception {
        UUID id = save("name-0", 1000, 5, false);
        read(id);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicBoolean writing = new AtomicBoolean(true);
        List<Future<Integer>> readers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Callable<Integer> reader = () -> {
                int bad = 0;
                for (int i = 0; i < 250; i++) {
                    JsonNode body = read(id);
                    int k = Integer.parseInt(body.get("name").asText().substring("name-".length()));
                    if (body.get("price").asInt() != 1000 + k) {
                        bad++;
                    }
                    Thread.sleep(2);
                }
                return bad;
            };
            readers.add(pool.submit(reader));
        }
        for (int k = 1; k <= 20; k++) {
            update(id, k);
            Thread.sleep(15);
        }
        int bad = 0;
        for (Future<Integer> f : readers) {
            bad += f.get();
        }
        pool.shutdown();

        assertThat(bad).as("이름과 가격이 어긋난 응답 수").isZero();
    }

    @Test
    @DisplayName("롤백된 갱신은 포인터와 값 키를 그대로 둔다")
    void updateProduct_롤백이면_포인터와_값_키가_그대로다() throws Exception {
        UUID id = save("name-0", 1000, 5, false);
        read(id);
        String pointerBefore = pointer(id);
        String bodyBefore = valueKeyBody(id);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            update(id, 9);
            throw new IllegalStateException("롤백");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(pointer(id)).isEqualTo(pointerBefore);
        assertThat(valueKeyBody(id)).isEqualTo(bodyBefore);
    }

    @Test
    @DisplayName("커밋 뒤 L1 이 비어 있는 다른 인스턴스도 30초를 기다리지 않고 L2 에서 새 이름을 읽고, 옛 값 키는 유예 뒤 사라진다")
    void updateProduct_커밋뒤_다른_인스턴스가_L2에서_새_이름을_읽는다() throws Exception {
        UUID id = save("name-0", 1000, 5, false);
        read(id);
        String oldPointer = pointer(id);
        update(id, 1);

        ProductDetailCache otherInstance = new ProductDetailCache(redis, jdbc, 1000, 200);
        CacheTestSupport.SQL.clear();
        var detail = otherInstance.read(id, key -> { throw new AssertionError("L2 에 있어야 한다 - DB 를 읽으면 안 된다"); });

        assertThat(detail).isPresent();
        assertThat(detail.get().body().name()).isEqualTo("name-1");
        assertThat(detail.get().body().price()).isEqualTo(1001);
        assertThat(pointer(id)).isNotEqualTo(oldPointer);
        await().atMost(Duration.ofSeconds(5)).until(() -> redis.opsForValue().get("product:" + id + ":v" + oldPointer) == null);
    }

    @Test
    @DisplayName("재고만 바뀌면 포인터가 움직이지 않는다")
    void increaseStock_재고만_바뀌면_포인터가_그대로다() throws Exception {
        UUID id = save("name-0", 1000, 5, false);
        read(id);
        String pointerBefore = pointer(id);

        productCommonService.increaseStock(id, 3);
        jdbc.update("UPDATE product SET stock = stock - 1 WHERE id = ?", id);

        assertThat(pointer(id)).isEqualTo(pointerBefore);
    }
}
