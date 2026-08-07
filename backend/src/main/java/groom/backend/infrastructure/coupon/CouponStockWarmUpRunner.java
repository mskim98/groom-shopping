package groom.backend.infrastructure.coupon;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.repository.CouponRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

// 부팅 시 활성 쿠폰의 남은 수량을 Redis 로 복사한다(워밍업).
//
// 없으면 모든 쿠폰의 첫 발급 요청이 NOT_INITIALIZED 로 DB 비관적 락 폴백을 탄다.
// 그 폴백은 Redis 와 DB 두 저장소에 걸친 구간이라 분산 락을 오래 잡는데,
// 이벤트 시작 순간이 곧 최대 트래픽 시점이라 하필 그때 요청이 폴백으로 몰린다.
//
// 상품 쪽 StockWarmUpRunner 와 의도적으로 다른 점: 덮어쓰지 않는다(SETNX).
// 상품은 DB 가 원천이고 Redis 는 선점 게이트라 부팅 시 Redis=DB 로 맞추는 것이 옳지만,
// 쿠폰은 이벤트 진행 중 Redis 가 살아 있는 카운터다. DB 수량은 커밋이 끝난 발급까지만 반영하므로,
// 재기동 시 SET 으로 덮으면 Lua 차감은 끝났으나 DB 커밋 전이던 요청 수만큼 재고가 되살아난다.
// 워밍업의 목적은 동기화가 아니라 "첫 요청이 폴백을 타지 않게 하는 것"이다.
// 상품 워밍업(StockWarmUpRunner)과는 다루는 Redis 키가 달라 실행 순서에 의존하지 않는다.
//
// @Order(1) : 구 키 이관(CouponRedisKeyMigrationRunner, @Order(0)) 다음에 돈다.
// 먼저 돌면 새 키를 SETNX 로 심어 버려 구 키에 남아 있던 카운터가 버려진다.
@Slf4j
@Order(1)
@Component
@RequiredArgsConstructor
public class CouponStockWarmUpRunner implements ApplicationRunner {

    private final CouponRepository couponRepository;
    private final CouponStockRedisRepository couponStockRedisRepository;

    // 한 번에 읽어올 페이지 크기(메모리/쿼리 횟수 트레이드오프)
    @Value("${coupon.warmup.batch-size:1000}")
    private int batchSize;

    @Override
    public void run(ApplicationArguments args) {
        int planted = 0;
        int skipped = 0;
        long lastId = 0L;
        List<Coupon> page;
        do {
            page = couponRepository.findActiveByIdGreaterThan(lastId, PageRequest.of(0, batchSize));
            for (Coupon coupon : page) {
                Long quantity = coupon.getQuantity();
                if (quantity != null && quantity > 0) {
                    if (couponStockRedisRepository.initStockIfAbsent(coupon.getId(), quantity)) {
                        planted++;
                    } else {
                        skipped++; // 이미 살아 있는 카운터가 있다 - 덮지 않는다
                    }
                }
                lastId = coupon.getId(); // 수량이 없거나 0 이어도 커서는 전진
            }
        } while (page.size() == batchSize); // 마지막(부족) 페이지면 종료

        log.info("[COUPON_STOCK_WARMUP] 쿠폰 재고 워밍업 완료 - 신규 {}건, 기존 유지 {}건 (batchSize={})",
                planted, skipped, batchSize);
    }
}
