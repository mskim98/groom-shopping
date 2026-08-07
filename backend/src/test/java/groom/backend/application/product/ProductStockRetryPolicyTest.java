package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

/**
 * 포트폴리오가 서술한 재시도 파라미터가 코드에 실제로 존재하는지 고정한다.
 * 문서와 코드가 어긋나면 이 테스트가 먼저 깨진다.
 */
@DisplayName("재고 재시도 정책")
class ProductStockRetryPolicyTest {

    private Backoff backoffOf(String methodName) throws NoSuchMethodException {
        Method method = ProductStockService.class.getMethod(methodName, UUID.class, int.class);
        Retryable retryable = method.getAnnotation(Retryable.class);
        assertThat(retryable).as("%s 에 @Retryable 이 있어야 한다", methodName).isNotNull();
        return retryable.backoff();
    }

    @Test
    @DisplayName("차감 경로 백오프는 100ms 시작·2배 증가·지터 사용·상한 1000ms 다")
    void decreaseBackoffUsesJitterAndCap() throws NoSuchMethodException {
        Backoff backoff = backoffOf("decreaseWithOptimisticLock");
        assertThat(backoff.delay()).isEqualTo(100L);
        assertThat(backoff.multiplier()).isEqualTo(2.0);
        assertThat(backoff.random()).isTrue();
        assertThat(backoff.maxDelay()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("증가(복원) 경로도 같은 백오프 정책을 쓴다")
    void increaseBackoffUsesJitterAndCap() throws NoSuchMethodException {
        Backoff backoff = backoffOf("increaseWithOptimisticLock");
        assertThat(backoff.random()).isTrue();
        assertThat(backoff.maxDelay()).isEqualTo(1000L);
    }
}
