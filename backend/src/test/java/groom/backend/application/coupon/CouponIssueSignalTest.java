package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@DisplayName("쿠폰 발급 완료 신호")
class CouponIssueSignalTest {

    @Test
    @DisplayName("Redis 발행이 실패해도 예외를 던지지 않는다 - 이미 커밋된 발급을 되돌릴 수 없고 대기자는 키를 다시 읽는다")
    void publish_Redis_오류는_삼킨다() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        given(redis.execute(any(RedisCallback.class))).willThrow(new QueryTimeoutException("시험용 Redis 장애"));

        assertThatCode(() -> new CouponIssueSignal(redis).publish(Map.of("req-1", "SUCCESS", "req-2", "FAILED:COUPON_OUT_OF_STOCK")))
                .doesNotThrowAnyException();
    }
}
