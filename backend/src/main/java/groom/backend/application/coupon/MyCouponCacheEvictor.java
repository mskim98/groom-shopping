package groom.backend.application.coupon;

import groom.backend.infrastructure.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * 내 쿠폰 목록 캐시(userId 키)를 비운다. searchMyCoupon 이 @Cacheable 로 읽는 목록이라 발급이 확정된 경로가 비우지 않으면
 * 방금 발급한 쿠폰이 캐시 TTL 동안 목록에 보이지 않는다. 스트림 컨슈머(확정 뒤)와 DB 경로 발급이 함께 쓴다
 *
 * <p>캐시를 끈 구성처럼 관리자가 없을 수도 있어 선택 주입이다
 */
@Slf4j
@Component
public class MyCouponCacheEvictor {

    private final ObjectProvider<CacheManager> couponCacheManager;

    public MyCouponCacheEvictor(@Qualifier("couponCacheManager") ObjectProvider<CacheManager> couponCacheManager) {
        this.couponCacheManager = couponCacheManager;
    }

    // 행이 커밋된 사용자의 목록 캐시를 비운다. 캐시 저장소(Redis) 장애로 발급을 실패시킬 이유는 없다 - 비우지 못하면 TTL 이 지날 때까지 목록이 낡을 뿐이다
    public void evict(Long userId) {
        CacheManager manager = couponCacheManager.getIfAvailable();
        if (manager == null) {
            return;
        }
        try {
            Cache cache = manager.getCache(RedisConfig.COUPON_LIST_CACHE_NAME);
            if (cache != null) {
                cache.evict(userId);
            }
        } catch (Exception e) {
            log.warn("[COUPON_MY_LIST_CACHE_EVICT_FAILED] userId={}, error={}", userId, e.toString());
        }
    }
}
