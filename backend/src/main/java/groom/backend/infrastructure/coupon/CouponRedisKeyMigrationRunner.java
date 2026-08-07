package groom.backend.infrastructure.coupon;

import groom.backend.application.coupon.CouponStockRedisRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

// 구 키(coupon:stock:1)를 해시태그 키(coupon:{1}:stock)로 1회 이관한다
//
// 재고 카운터를 새로 심지 않고 RENAME 으로 옮기는 이유 : 이벤트 진행 중이면 구 키가 살아 있는
// 카운터다. DB 수량으로 다시 심으면 Lua 차감은 끝났으나 DB 커밋 전이던 요청 수만큼 재고가 되살아난다
//
// 새 키가 이미 있으면 덮지 않는다. 새 키 쪽이 현재 진실이고, 구 키는 배포 이전의 잔재다
//
// @Order(0) : 워밍업(CouponStockWarmUpRunner, @Order(1)) 보다 먼저 돈다
// 순서가 뒤집히면 워밍업이 새 키를 SETNX 로 심어 버려 구 키의 카운터가 버려진다
//
// 이 러너는 한 번 배포되면 역할이 끝난다. 다음 릴리스에서 삭제해도 된다
@Slf4j
@Order(0)
@Component
@RequiredArgsConstructor
public class CouponRedisKeyMigrationRunner implements ApplicationRunner {

    private static final String LEGACY_STOCK_PREFIX = "coupon:stock:";
    private static final String LEGACY_ISSUED_USERS_PREFIX = "coupon:issued_users:";

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        int moved = 0;
        // 구 키는 SCAN 으로 훑는다. KEYS 는 단일 스레드 Redis 를 멈추므로 쓰지 않는다
        for (Long couponId : scanLegacyCouponIds()) {
            moved += migrateCoupon(couponId);
        }
        log.info("[COUPON_REDIS_KEY_MIGRATION] 구 키 이관 완료 - {}건", moved);
    }

    // 한 쿠폰의 구 키 두 개를 새 키로 옮긴다. 옮긴 키 수를 반환한다
    public int migrateCoupon(Long couponId) {
        int moved = 0;
        moved += renameIfSafe(LEGACY_STOCK_PREFIX + couponId,
                CouponStockRedisRepository.stockKey(couponId));
        moved += renameIfSafe(LEGACY_ISSUED_USERS_PREFIX + couponId,
                CouponStockRedisRepository.issuedUsersKey(couponId));
        return moved;
    }

    private int renameIfSafe(String legacyKey, String newKey) {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(legacyKey))) {
            return 0;
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(newKey))) {
            log.warn("[COUPON_REDIS_KEY_MIGRATION_SKIP] 새 키가 이미 있어 덮지 않는다 - {}", newKey);
            return 0;
        }
        redisTemplate.rename(legacyKey, newKey);
        return 1;
    }

    // 구 재고 키에서 couponId 를 추출한다. 발급자 SET 은 재고 키와 짝이므로 따로 훑지 않는다
    // KEYS 가 아니라 SCAN 을 쓴다 - KEYS 는 단일 스레드 Redis 를 키 수만큼 멈춘다
    private Set<Long> scanLegacyCouponIds() {
        Set<Long> ids = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(LEGACY_STOCK_PREFIX + "*").count(500).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String suffix = key.substring(LEGACY_STOCK_PREFIX.length());
                try {
                    ids.add(Long.parseLong(suffix));
                } catch (NumberFormatException ignored) {
                    log.warn("[COUPON_REDIS_KEY_MIGRATION_SKIP] couponId 를 읽을 수 없는 키 - {}", key);
                }
            }
        }
        return ids;
    }
}
