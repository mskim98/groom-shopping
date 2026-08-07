package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class ProductStockRedisRepositoryTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @InjectMocks
    private ProductStockRedisRepository repository;

    private final UUID productId = UUID.randomUUID();

    @Test
    void preReserve_선점_성공시_남은_수량을_반환한다() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any())).willReturn(5L);

        long result = repository.preReserve(productId, 2);

        assertThat(result).isEqualTo(5L);
    }

    @Test
    void preReserve_결과가_null이면_미초기화_코드_마이너스2를_반환한다() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any())).willReturn(null);

        long result = repository.preReserve(productId, 1);

        assertThat(result).isEqualTo(-2L);
    }

    @Test
    void release_선점_재고를_INCRBY로_복원한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);

        repository.release(productId, 3);

        then(valueOps).should().increment(eq("product:stock:" + productId), eq(3L));
    }
}
