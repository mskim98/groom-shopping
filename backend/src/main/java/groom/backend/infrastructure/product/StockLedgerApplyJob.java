package groom.backend.infrastructure.product;

import groom.backend.application.product.StockLedgerRepository;
import groom.backend.application.product.StockLedgerService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 재고 원장 적용 배치. 판매는 원장에 INSERT 로만 남고, 이 배치가 상품마다 미적용 행의 부호 있는 합을 product.stock 에 한 번 반영한다
// 상품 행 쓰기가 결제마다 한 번이 아니라 주기마다 한 번이라 한 상품 행에 소비자 트랜잭션이 줄서지 않는다
// 인스턴스가 여럿이어도 상품 행을 SKIP LOCKED 로 잡아 한 상품은 한 인스턴스만 처리한다
@Slf4j
@Component
@RequiredArgsConstructor
public class StockLedgerApplyJob {

    private final StockLedgerRepository ledger;
    private final StockLedgerService service;

    // initialDelay 도 주기와 같게 둔다 - 시험 컨텍스트가 기동 직후 다른 시험의 원장 행을 먼저 접어 넣지 않게 한다
    @Scheduled(fixedDelayString = "${stock.ledger.apply-interval-ms:1000}", initialDelayString = "${stock.ledger.apply-interval-ms:1000}")
    public void apply() {
        int applied = applyOnce();
        if (applied > 0) {
            log.debug("[STOCK_LEDGER_APPLY] 적용 행 수 - {}", applied);
        }
    }

    /**
     * 미적용 행이 있는 상품을 모두 적용한다. 한 상품의 실패는 그 상품의 트랜잭션만 롤백하고 다른 상품을 막지 않는다
     *
     * @return 적용한 원장 행 수
     */
    public int applyOnce() {
        int applied = 0;
        for (UUID productId : ledger.findProductIdsWithUnapplied()) {
            try {
                applied += service.applyProduct(productId);
            } catch (Exception e) {
                log.error("[STOCK_LEDGER_APPLY_FAILED] 다음 회차에 다시 시도 - ProductId: {}, Error: {}", productId, e.getMessage(), e);
            }
        }
        return applied;
    }
}
