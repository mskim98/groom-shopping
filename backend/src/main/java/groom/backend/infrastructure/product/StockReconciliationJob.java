package groom.backend.infrastructure.product;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockLedgerRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis 선점 재고와 DB 재고를 주기적으로 대조한다.
 * <p>
 * DB 쪽 값은 상품 종류에 따라 다르다. 플래시 세일 상품(REDIS 모드)은 판매가 원장에 INSERT 로 남고 배치가 늦게 접어 넣으므로
 * 남은 재고 = product.stock − SUM(미적용 원장 qty) 와 Redis 를 비교한다. DB 모드 플래시 상품은 Redis 키가 쓰이지 않으므로 건너뛴다.
 * 그 밖의 상품은 product.stock 과 그대로 비교한다.
 * <p>
 * 판정은 방향과 지속성 두 축으로 나눈다.
 * - redis > db : 선점은 Redis 를 줄이기만 하므로 정상 동작으로는 만들 수 없는 방향 -> 1회 관측으로 이상 확정
 * - redis < db : 인플라이트 선점과 구별 불가 -> 연속 2회 스캔에서 같은 gap 이 유지될 때만 지속 드리프트로 승격
 * - 키 부재   : 게이트가 열린 상태(preReserve 가 -2 로 DB 모드 전환) -> 별도 계상
 * <p>
 * 자동 보정은 하지 않는다. 보정이 새 드리프트 원인이 될 수 있어 탐지와 경보까지만 담당한다(복귀 때의 덮어쓰기는 StockModeRecoveryJob 몫).
 * 순회는 StockWarmUpRunner 와 같은 키셋 페이지네이션을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconciliationJob {

    private static final UUID MIN_UUID = new UUID(0L, 0L);

    private final SpringDataProductRepository productRepository;
    private final ProductStockRedisRepository stockRedisRepository;
    private final StockLedgerRepository stockLedgerRepository;
    private final MeterRegistry meterRegistry;

    @Value("${stock.reconcile.batch-size:1000}")
    private int batchSize = 1000;

    // 직전 스캔에서 관측한 (db - redis) gap. 다음 스캔에서 줄지 않으면 지속으로 승격
    private final Map<UUID, Integer> previousUnderGap = new HashMap<>();

    private final AtomicInteger overCount = new AtomicInteger();
    private final AtomicInteger underPersistentCount = new AtomicInteger();
    private final AtomicInteger missingKeyCount = new AtomicInteger();
    private final AtomicInteger maxAbsGap = new AtomicInteger();

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("stock_drift_over", overCount, AtomicInteger::get)
                .description("Redis 재고가 DB 보다 큰 상품 수 (정상 경로로는 불가능한 방향)")
                .register(meterRegistry);
        Gauge.builder("stock_drift_under_persistent", underPersistentCount, AtomicInteger::get)
                .description("Redis 재고가 DB 보다 작고 연속 2회 스캔에서 gap 이 줄지 않은 상품 수")
                .register(meterRegistry);
        Gauge.builder("stock_drift_missing_key", missingKeyCount, AtomicInteger::get)
                .description("Redis 재고 키가 없는 상품 수 (게이트 미동작)")
                .register(meterRegistry);
        Gauge.builder("stock_drift_max_abs_gap", maxAbsGap, AtomicInteger::get)
                .description("관측된 |DB - Redis| 최댓값")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${stock.reconcile.interval-ms:60000}")
    public void reconcile() {
        ReconcileResult result = reconcileOnce();
        if (result.over() > 0 || result.underPersistent() > 0) {
            log.warn("[STOCK_DRIFT] 대조 이상 - scanned={}, over={}, underPersistent={}, missingKey={}, maxAbsGap={}",
                    result.scanned(), result.over(), result.underPersistent(),
                    result.missingKey(), result.maxAbsGap());
        } else {
            log.info("[STOCK_RECONCILE] 대조 정상 - scanned={}, missingKey={}",
                    result.scanned(), result.missingKey());
        }
    }

    public ReconcileResult reconcileOnce() {
        int scanned = 0;
        int over = 0;
        int underPersistent = 0;
        int missing = 0;
        int maxGap = 0;
        Map<UUID, Integer> currentUnderGap = new HashMap<>();

        UUID lastId = MIN_UUID;
        List<ProductJpaEntity> page;
        do {
            page = productRepository.findByIdGreaterThanOrderByIdAsc(lastId, PageRequest.of(0, batchSize));
            if (page.isEmpty()) {
                break;
            }
            List<UUID> ids = new ArrayList<>(page.size());
            for (ProductJpaEntity product : page) {
                ids.add(product.getId());
                lastId = product.getId();
            }
            List<Integer> redisStocks = stockRedisRepository.getStocks(ids);
            // REDIS 모드 플래시 상품만 미적용 원장 합을 뺀다(한 번의 조회로 페이지 전체를 읽는다)
            List<UUID> redisModeFlashIds = page.stream().filter(StockReconciliationJob::isRedisModeFlash)
                    .map(ProductJpaEntity::getId).toList();
            Map<UUID, Integer> unapplied = stockLedgerRepository.sumUnappliedByProduct(redisModeFlashIds);

            for (int i = 0; i < page.size(); i++) {
                ProductJpaEntity product = page.get(i);
                Integer dbStock = product.getStock();
                Integer redisStock = redisStocks.get(i);
                if (dbStock == null || isDbModeFlash(product)) {
                    continue;
                }
                if (isRedisModeFlash(product)) {
                    dbStock -= unapplied.getOrDefault(product.getId(), 0);
                }
                scanned++;
                if (redisStock == null) {
                    missing++;
                    continue;
                }
                int gap = dbStock - redisStock;
                maxGap = Math.max(maxGap, Math.abs(gap));
                if (gap < 0) {
                    // Redis 가 더 많다 - 정상 경로로는 만들 수 없는 방향이라 1회로 확정
                    over++;
                    log.warn("[STOCK_DRIFT_OVER] productId={}, db={}, redis={}",
                            product.getId(), dbStock, redisStock);
                } else if (gap > 0) {
                    // Redis 가 더 적다 - 인플라이트 선점과 구별 불가하므로 지속성으로 판정
                    currentUnderGap.put(product.getId(), gap);
                    Integer before = previousUnderGap.get(product.getId());
                    if (before != null && gap >= before) {
                        underPersistent++;
                        log.warn("[STOCK_DRIFT_UNDER_PERSISTENT] productId={}, db={}, redis={}, gap={}, prevGap={}",
                                product.getId(), dbStock, redisStock, gap, before);
                    }
                }
            }
        } while (page.size() == batchSize);

        previousUnderGap.clear();
        previousUnderGap.putAll(currentUnderGap);

        overCount.set(over);
        underPersistentCount.set(underPersistent);
        missingKeyCount.set(missing);
        maxAbsGap.set(maxGap);

        return new ReconcileResult(scanned, over, underPersistent, missing, maxGap);
    }

    private static boolean isRedisModeFlash(ProductJpaEntity product) {
        return Boolean.TRUE.equals(product.getFlashSale()) && !"DB".equals(product.getStockMode());
    }

    private static boolean isDbModeFlash(ProductJpaEntity product) {
        return Boolean.TRUE.equals(product.getFlashSale()) && "DB".equals(product.getStockMode());
    }

    public record ReconcileResult(int scanned, int over, int underPersistent, int missingKey, int maxAbsGap) {
    }
}
