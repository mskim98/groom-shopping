package groom.backend.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockLedgerRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@DisplayName("Redis-DB 재고 대조 판정")
class StockReconciliationJobTest {

    @Mock
    private SpringDataProductRepository productRepository;
    @Mock
    private ProductStockRedisRepository stockRedisRepository;
    @Mock
    private StockLedgerRepository stockLedgerRepository;

    private MeterRegistry meterRegistry;
    private StockReconciliationJob job;

    private final UUID over = new UUID(0L, 1L);
    private final UUID under = new UUID(0L, 2L);
    private final UUID missing = new UUID(0L, 3L);

    private ProductJpaEntity entity(UUID id, int stock) {
        return ProductJpaEntity.builder().id(id).name("p").price(1).stock(stock)
                .isActive(true).category("GENERAL").status("AVAILABLE").thresholdValue(10).build();
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        job = new StockReconciliationJob(productRepository, stockRedisRepository, stockLedgerRepository, meterRegistry);
        job.registerGauges();
        // reconcileOnce() 를 두 번 호출하는 테스트가 있으므로 willReturn 체인이 아니라
        // 커서 기반 willAnswer 로 매 호출마다 "첫 페이지 -> 빈 페이지" 가 되풀이되게 한다
        lenient().when(productRepository.findByIdGreaterThanOrderByIdAsc(any(UUID.class), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    UUID cursor = invocation.getArgument(0);
                    return cursor.equals(new UUID(0L, 0L))
                            ? List.of(entity(over, 10), entity(under, 10), entity(missing, 10))
                            : List.<ProductJpaEntity>of();
                });
        lenient().when(stockRedisRepository.getStocks(anyList()))
                .thenReturn(Arrays.asList(12, 7, null));
    }

    @Test
    @DisplayName("Redis 가 DB 보다 많으면 1회 관측으로 즉시 이상 판정한다")
    void redisGreaterThanDbIsImmediateDrift() {
        StockReconciliationJob.ReconcileResult result = job.reconcileOnce();

        assertThat(result.over()).isEqualTo(1);
        assertThat(result.missingKey()).as("플래시가 아닌 상품은 Redis 키가 없는 것이 정상이라 부재로 세지 않는다").isZero();
        assertThat(meterRegistry.get("stock_drift_over").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 가 DB 보다 적은 것은 1회차엔 인플라이트 선점으로 보고, 2회차에도 같으면 지속 드리프트로 승격한다")
    void redisLessThanDbNeedsTwoConsecutiveScans() {
        assertThat(job.reconcileOnce().underPersistent()).isZero();
        assertThat(job.reconcileOnce().underPersistent()).isEqualTo(1);
        assertThat(meterRegistry.get("stock_drift_under_persistent").gauge().value()).isEqualTo(1.0);
    }

    private ProductJpaEntity flashEntity(UUID id, int stock, String mode) {
        return ProductJpaEntity.builder().id(id).name("p").price(1).stock(stock)
                .isActive(true).category("GENERAL").status("AVAILABLE").thresholdValue(10)
                .flashSale(true).stockMode(mode).build();
    }

    // 페이지 하나만 돌려주고 다음 커서에서는 빈 페이지를 돌려준다
    private void onePage(List<ProductJpaEntity> page) {
        doAnswer(invocation -> ((UUID) invocation.getArgument(0)).equals(new UUID(0L, 0L))
                ? page : List.<ProductJpaEntity>of())
                .when(productRepository).findByIdGreaterThanOrderByIdAsc(any(UUID.class), any(Pageable.class));
    }

    @Test
    @DisplayName("REDIS 모드 플래시 상품은 stock − 미적용 합과 Redis 를 비교한다 - 재고 10 · 미적용 3 · Redis 7 이면 드리프트 없음")
    void 플래시_REDIS모드는_재고에서_미적용합을_뺀_값과_비교한다() {
        UUID flash = new UUID(0L, 10L);
        onePage(List.of(flashEntity(flash, 10, "REDIS")));
        doReturn(Arrays.asList(7)).when(stockRedisRepository).getStocks(anyList());
        doReturn(Map.of(flash, 3)).when(stockLedgerRepository).sumUnappliedByProduct(anyList());

        job.reconcileOnce();
        StockReconciliationJob.ReconcileResult second = job.reconcileOnce();

        assertThat(second.over()).isZero();
        assertThat(second.underPersistent()).isZero();
        assertThat(second.maxAbsGap()).isZero();
    }

    @Test
    @DisplayName("미적용 합을 빼지 않으면 같은 값이 지속 드리프트로 보이므로, Redis 가 남은 재고보다 크면 over 로 판정한다")
    void 플래시_REDIS모드_Redis가_남은재고보다_크면_over다() {
        UUID flash = new UUID(0L, 11L);
        onePage(List.of(flashEntity(flash, 10, "REDIS")));
        doReturn(Arrays.asList(8)).when(stockRedisRepository).getStocks(anyList());
        doReturn(Map.of(flash, 3)).when(stockLedgerRepository).sumUnappliedByProduct(anyList());

        assertThat(job.reconcileOnce().over()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 모드 플래시 상품은 대조에서 건너뛴다")
    void 플래시_DB모드는_건너뛴다() {
        UUID flash = new UUID(0L, 12L);
        onePage(List.of(flashEntity(flash, 10, "DB")));
        doReturn(Arrays.asList(99)).when(stockRedisRepository).getStocks(anyList());

        StockReconciliationJob.ReconcileResult result = job.reconcileOnce();

        assertThat(result.scanned()).isZero();
        assertThat(result.over()).isZero();
        assertThat(result.missingKey()).isZero();
    }

    @Test
    @DisplayName("일반 상품은 예전처럼 DB 재고와 Redis 를 그대로 비교한다")
    void 일반상품은_DB재고와_Redis를_그대로_비교한다() {
        UUID plain = new UUID(0L, 13L);
        onePage(List.of(entity(plain, 10)));
        doReturn(Arrays.asList(12)).when(stockRedisRepository).getStocks(anyList());
        doReturn(Map.of(plain, 3)).when(stockLedgerRepository).sumUnappliedByProduct(anyList());

        assertThat(job.reconcileOnce().over()).as("일반 상품의 미적용 합은 쓰지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("REDIS 모드 플래시 상품의 Redis 키 부재는 계수하고, 플래시가 아닌 상품의 부재는 계수하지 않는다")
    void 키_부재는_REDIS모드_플래시_상품만_계수한다() {
        UUID flash = new UUID(0L, 14L);
        UUID plain = new UUID(0L, 15L);
        onePage(List.of(flashEntity(flash, 10, "REDIS"), entity(plain, 10)));
        doReturn(Arrays.asList(null, null)).when(stockRedisRepository).getStocks(anyList());
        doReturn(Map.of()).when(stockLedgerRepository).sumUnappliedByProduct(anyList());

        StockReconciliationJob.ReconcileResult result = job.reconcileOnce();

        assertThat(result.missingKey()).isEqualTo(1);
        assertThat(meterRegistry.get("stock_drift_missing_key").gauge().value()).isEqualTo(1.0);
    }
}
