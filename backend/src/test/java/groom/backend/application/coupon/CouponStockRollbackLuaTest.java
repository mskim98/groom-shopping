package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 재고 반납이 키를 만들지 않는다 (실 Redis 필요)
 *
 * <p>INCR 은 없는 키를 1 로 만든다. Redis 가 비워진 직후 반납이 한 번 들어오면
 * 재고 키가 1 로 생겨 "키 없음(-2) → 503" 보호가 사라지고, 한 장짜리 재고로 접수가 열린다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("쿠폰 재고 반납 Lua")
class CouponStockRollbackLuaTest {

    private static final long COUPON_ID = 9_100_000_001L;
    private static final long USER_ID = 77L;

    @Autowired
    private CouponStockRedisRepository repository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    @AfterEach
    void cleanKeys() {
        redisTemplate.delete(List.of(CouponStockRedisRepository.stockKey(COUPON_ID),
                CouponStockRedisRepository.issuedUsersKey(COUPON_ID),
                CouponStockRedisRepository.streamKey(COUPON_ID),
                CouponStockRedisRepository.statusKey("rollback-lua-probe")));
    }

    @Test
    @DisplayName("rollbackIssue 는 재고 키가 있으면 +1 하고 발급자 SET 에서 뺀다")
    void rollbackIssue_키가_있으면_재고를_올리고_명단에서_뺀다() {
        repository.initStock(COUPON_ID, 5L);
        repository.addIssuedUsers(COUPON_ID, List.of(USER_ID));

        repository.rollbackIssue(COUPON_ID, USER_ID);

        assertThat(repository.getStock(COUPON_ID)).isEqualTo(6L);
        assertThat(repository.getIssuedUsers(COUPON_ID)).doesNotContain(String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("rollbackIssue 는 재고 키가 없으면 키를 만들지 않고 발급자 SET 도 건드리지 않는다")
    void rollbackIssue_키가_없으면_만들지_않는다() {
        repository.addIssuedUsers(COUPON_ID, List.of(USER_ID));

        repository.rollbackIssue(COUPON_ID, USER_ID);

        assertThat(repository.getStock(COUPON_ID)).isNull();
        assertThat(redisTemplate.hasKey(CouponStockRedisRepository.stockKey(COUPON_ID))).isFalse();
        // 키가 없으면 복구가 DB 로 명단과 재고를 다시 세운다. 여기서 명단만 줄이면 둘이 어긋난다
        assertThat(repository.getIssuedUsers(COUPON_ID)).contains(String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("rollbackIssue 를 같은 예약에 두 번 불러도 재고는 한 번만 올라간다")
    void rollbackIssue_같은_예약에_두번_불러도_한번만_올린다() {
        repository.initStock(COUPON_ID, 5L);
        repository.addIssuedUsers(COUPON_ID, List.of(USER_ID));

        repository.rollbackIssue(COUPON_ID, USER_ID);
        // 재전달된 배치가 같은 정리를 다시 부르는 경우 - 이미 풀린 예약이라 올릴 재고가 없다
        repository.rollbackIssue(COUPON_ID, USER_ID);

        assertThat(repository.getStock(COUPON_ID)).isEqualTo(6L);
    }

    @Test
    @DisplayName("rollbackStockOnly 는 재고 키가 있으면 +1 한다")
    void rollbackStockOnly_키가_있으면_재고를_올린다() {
        repository.initStock(COUPON_ID, 5L);

        repository.rollbackStockOnly(COUPON_ID);

        assertThat(repository.getStock(COUPON_ID)).isEqualTo(6L);
    }

    @Test
    @DisplayName("rollbackStockOnly 는 재고 키가 없으면 키를 만들지 않는다")
    void rollbackStockOnly_키가_없으면_만들지_않는다() {
        repository.rollbackStockOnly(COUPON_ID);

        assertThat(redisTemplate.hasKey(CouponStockRedisRepository.stockKey(COUPON_ID))).isFalse();
        // 키가 만들어졌다면 enqueue 가 -2(미초기화) 대신 통과를 돌려주게 된다
        assertThat(repository.enqueue(COUPON_ID, USER_ID, "rollback-lua-probe"))
                .isEqualTo(CouponStockRedisRepository.IssueResult.NOT_INITIALIZED);
    }
}
