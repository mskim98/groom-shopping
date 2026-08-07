package groom.backend.infrastructure.product;

import groom.backend.application.product.ProductStockRedisRepository;
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
 * Redis 게이트 재고와 DB 원천 재고를 주기적으로 대조한다.
 * <p>
 * 판정은 방향과 지속성 두 축으로 나눈다.
 * - redis > db : 선점은 Redis 를 줄이기만 하므로 정상 동작으로는 만들 수 없는 방향 -> 1회 관측으로 이상 확정
 * - redis < db : 인플라이트 선점과 구별 불가 -> 연속 2회 스캔에서 같은 gap 이 유지될 때만 지속 드리프트로 승격
 * - 키 부재   : 게이트가 열린 상태(preReserve 가 -2 로 DB 폴백) -> 별도 계상
 * <p>
 * 자동 보정은 하지 않는다. 보정이 새 드리프트 원인이 될 수 있어 탐지와 경보까지만 담당한다.
 * 순회는 StockWarmUpRunner 와 같은 키셋 페이지네이션을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconciliationJob {

    private static final UUID MIN_UUID = new UUID(0L, 0L);

    private final SpringDataProductRepository productRepository;
    private final ProductStockRedisRepository stockRedisRepository;
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

            for (int i = 0; i < page.size(); i++) {
                ProductJpaEntity product = page.get(i);
                Integer dbStock = product.getStock();
                Integer redisStock = redisStocks.get(i);
                if (dbStock == null) {
                    continue;
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

    public record ReconcileResult(int scanned, int over, int underPersistent, int missingKey, int maxAbsGap) {
    }
}
