package groom.backend.infrastructure.product;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockLedgerRepository;
import groom.backend.application.product.StockLedgerService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 장애로 DB 모드가 된 플래시 상품을 Redis 모드로 되돌리는 배치. 대기 시간(min-db-dwell-ms)이 지나고 Redis PING 이 응답할 때만 움직인다
// 대기 시간이 Toss 응답 한도(60초)보다 길어야 옛 Redis 에서 선점한 모든 결제가 TX1 에 닿았거나 실패한 뒤에 복귀한다
@Slf4j
@Component
@RequiredArgsConstructor
public class StockModeRecoveryJob {

    private final StockLedgerRepository ledger;
    private final StockLedgerService service;
    private final ProductStockRedisRepository redis;

    @Value("${stock.mode.min-db-dwell-ms:70000}")
    private long minDbDwellMs = 70000;

    @Scheduled(fixedDelayString = "${stock.mode.recovery-interval-ms:10000}", initialDelayString = "${stock.mode.recovery-interval-ms:10000}")
    public void recover() {
        recoverOnce();
    }

    /**
     * 복귀할 상품이 있고 Redis 가 응답하면 상품마다 복귀 트랜잭션을 돌린다
     *
     * @return 복귀한 상품 수
     */
    public int recoverOnce() {
        List<UUID> due = ledger.findDbModeProductsPastDwell(minDbDwellMs);
        if (due.isEmpty() || !redis.ping()) {
            return 0;
        }
        int returned = 0;
        for (UUID productId : due) {
            try {
                if (service.returnToRedis(productId, minDbDwellMs)) {
                    returned++;
                }
            } catch (Exception e) {
                log.error("[STOCK_MODE_RETURN_FAILED] DB 모드로 남김, 다음 회차에 다시 시도 - ProductId: {}, Error: {}",
                        productId, e.getMessage(), e);
            }
        }
        return returned;
    }
}
