package groom.backend.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
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
        job = new StockReconciliationJob(productRepository, stockRedisRepository, meterRegistry);
        job.registerGauges();
        // reconcileOnce() 를 두 번 호출하는 테스트가 있으므로 willReturn 체인이 아니라
        // 커서 기반 willAnswer 로 매 호출마다 "첫 페이지 -> 빈 페이지" 가 되풀이되게 한다
        given(productRepository.findByIdGreaterThanOrderByIdAsc(any(UUID.class), any(Pageable.class)))
                .willAnswer(invocation -> {
                    UUID cursor = invocation.getArgument(0);
                    return cursor.equals(new UUID(0L, 0L))
                            ? List.of(entity(over, 10), entity(under, 10), entity(missing, 10))
                            : List.<ProductJpaEntity>of();
                });
        given(stockRedisRepository.getStocks(anyList()))
                .willReturn(Arrays.asList(12, 7, null));
    }

    @Test
    @DisplayName("Redis 가 DB 보다 많으면 1회 관측으로 즉시 이상 판정한다")
    void redisGreaterThanDbIsImmediateDrift() {
        StockReconciliationJob.ReconcileResult result = job.reconcileOnce();

        assertThat(result.over()).isEqualTo(1);
        assertThat(result.missingKey()).isEqualTo(1);
        assertThat(meterRegistry.get("stock_drift_over").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 가 DB 보다 적은 것은 1회차엔 인플라이트 선점으로 보고, 2회차에도 같으면 지속 드리프트로 승격한다")
    void redisLessThanDbNeedsTwoConsecutiveScans() {
        assertThat(job.reconcileOnce().underPersistent()).isZero();
        assertThat(job.reconcileOnce().underPersistent()).isEqualTo(1);
        assertThat(meterRegistry.get("stock_drift_under_persistent").gauge().value()).isEqualTo(1.0);
    }
}
