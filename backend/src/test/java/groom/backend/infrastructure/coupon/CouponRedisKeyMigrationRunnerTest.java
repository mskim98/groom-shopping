package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import groom.backend.application.coupon.CouponStockRedisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 Redis 구 키 이관 러너")
class CouponRedisKeyMigrationRunnerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @InjectMocks
    private CouponRedisKeyMigrationRunner runner;

    @Test
    @DisplayName("새 키가 없고 구 키가 있으면 이름을 바꾼다")
    void migrateCoupon_구키만_있으면_RENAME한다() {
        given(redisTemplate.hasKey("coupon:stock:1")).willReturn(true);
        given(redisTemplate.hasKey(CouponStockRedisRepository.stockKey(1L))).willReturn(false);
        given(redisTemplate.hasKey("coupon:issued_users:1")).willReturn(false);

        int moved = runner.migrateCoupon(1L);

        assertThat(moved).isEqualTo(1);
        verify(redisTemplate).rename("coupon:stock:1", CouponStockRedisRepository.stockKey(1L));
    }

    @Test
    @DisplayName("새 키가 이미 있으면 구 키를 덮지 않는다")
    void migrateCoupon_새키가_있으면_덮지_않는다() {
        given(redisTemplate.hasKey("coupon:stock:1")).willReturn(true);
        given(redisTemplate.hasKey(CouponStockRedisRepository.stockKey(1L))).willReturn(true);
        given(redisTemplate.hasKey("coupon:issued_users:1")).willReturn(false);

        int moved = runner.migrateCoupon(1L);

        assertThat(moved).isZero();
        verify(redisTemplate, never()).rename("coupon:stock:1", CouponStockRedisRepository.stockKey(1L));
    }

    @Test
    @DisplayName("새 키 형식은 couponId 를 해시태그로 감싼다")
    void stockKey_해시태그로_감싼다() {
        assertThat(CouponStockRedisRepository.stockKey(1L)).isEqualTo("coupon:{1}:stock");
        assertThat(CouponStockRedisRepository.issuedUsersKey(1L)).isEqualTo("coupon:{1}:issued_users");
    }
}
