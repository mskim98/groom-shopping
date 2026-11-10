package groom.backend.application.product;

import groom.backend.application.product.StockLedgerRepository.LedgerRow;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.product.model.enums.StockMode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 플래시 세일 재고 원장의 트랜잭션 단위 작업. 상품 행 하나를 한 트랜잭션으로 다룬다.
 * <p>
 * 잠금 순서는 상품 행 → 원장 행이다. 적용 배치 · 전환 · 복귀가 모두 이 순서라 서로 기다리다 교착하지 않는다.
 * 남은 재고 = product.stock − SUM(미적용 qty) 은 stock 변경과 적용 표시를 한 트랜잭션으로 커밋하므로 어느 순간에도 성립한다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockLedgerService {

    /** DB 모드 진입 예약의 결과 */
    public enum DbReservation {
        RESERVED,
        /** 이 결제의 SALE 행이 이미 있다 - 먼저 온 요청이 예약했으므로 이 요청은 아무것도 예약하지 않았다 */
        DUPLICATE,
        SHORT,
        /** 그사이 REDIS 모드로 복귀했다 - Redis 로 한 번 더 시도한다 */
        MODE_FLIPPED
    }

    private final StockLedgerRepository ledger;
    private final ProductStockRedisRepository redis;

    /**
     * 한 상품의 미적용 행을 product.stock 에 접어 넣고 적용 표시를 한다. 다른 인스턴스가 이 상품을 처리 중이면 건너뛴다
     *
     * @return 적용한 행 수
     */
    @Transactional
    public int applyProduct(UUID productId) {
        if (!ledger.lockProductSkipLocked(productId)) {
            return 0;
        }
        return foldUnapplied(productId);
    }

    // 상품 행을 이미 잠근 트랜잭션 안에서만 부른다
    private int foldUnapplied(UUID productId) {
        List<LedgerRow> rows = ledger.claimUnapplied(productId);
        if (rows.isEmpty()) {
            return 0;
        }
        ledger.lowerStock(productId, rows.stream().mapToInt(LedgerRow::qty).sum());
        ledger.markApplied(rows.stream().map(LedgerRow::id).toList());
        return rows.size();
    }

    /**
     * 장애 전환. 상품 행을 잠그고 미적용 행을 접어 넣은 뒤 DB 모드로 바꾼다. 이미 DB 모드면 아무것도 하지 않는다
     * 잠금 없는 읽기를 먼저 해 DB 모드 동안 결제마다 상품 행을 잠그지 않는다
     */
    @Transactional
    public void switchToDb(UUID productId) {
        if (ledger.readMode(productId) == StockMode.DB) {
            return;
        }
        if (ledger.lockModeExclusive(productId) == StockMode.DB) {
            return;
        }
        foldUnapplied(productId);
        ledger.setModeDb(productId);
        log.warn("[STOCK_MODE_SWITCH] REDIS -> DB - ProductId: {}", productId);
    }

    /**
     * DB 모드 진입 예약. SALE(applied=true) 행을 먼저 쓰고 조건부 UPDATE 로 재고를 낮춘다. 재고가 모자라면 같은 트랜잭션을 롤백해 행도 없앤다
     * 호출한 쪽은 Toss 호출 전에 이 트랜잭션이 끝난 상태다
     */
    @Transactional
    public DbReservation reserveInDb(UUID paymentId, UUID productId, int quantity) {
        if (!ledger.insertLedger(paymentId, productId, quantity, StockLedgerRepository.Kind.SALE, true)) {
            return DbReservation.DUPLICATE;
        }
        if (ledger.decreaseInDbModeIfEnough(productId, quantity) == 1) {
            return DbReservation.RESERVED;
        }
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return ledger.readMode(productId) == StockMode.REDIS ? DbReservation.MODE_FLIPPED : DbReservation.SHORT;
    }

    /**
     * TX1(승인 트랜잭션)의 원장 기록. Redis 로 선점한 항목 하나를 호출한 트랜잭션에 합류해 기록한다.
     * 조건부 UPDATE 를 먼저 한다 - 공유 잠금으로 읽은 뒤 같은 행을 UPDATE 로 올리면 인플라이트 결제 둘이 서로를 기다려 교착한다
     * UPDATE 가 0행이면 공유 잠금으로 모드를 읽는다. REDIS 면 미적용 SALE 을 쓰고(잠금이 커밋까지 전환을 막는다), DB 면 재고 부족이다
     * 그 사이 모드가 바뀌었을 수 있어 한 번 더 시도한다
     */
    @Transactional
    public void recordSale(UUID paymentId, UUID productId, int quantity) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (ledger.decreaseInDbModeIfEnough(productId, quantity) == 1) {
                if (!ledger.insertLedger(paymentId, productId, quantity, StockLedgerRepository.Kind.SALE, true)) {
                    // 이미 SALE 이 있는데 재고를 또 낮췄다 - 롤백해 이중 차감을 막는다
                    throw new IllegalStateException("이미 판매가 기록된 결제를 DB 모드로 다시 차감하려 함 - paymentId=" + paymentId);
                }
                return;
            }
            if (ledger.lockModeShared(productId) == StockMode.REDIS) {
                ledger.insertLedger(paymentId, productId, quantity, StockLedgerRepository.Kind.SALE, false);
                return;
            }
        }
        throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
    }

    /**
     * 복귀. 상품 행을 잠그고 DB 모드와 대기 시간을 다시 확인한 뒤, Redis 키를 stock − 미적용 합으로 덮어쓰고 REDIS 모드로 바꾼다
     * NX 가 아니라 덮어쓰는 이유 - 장애 때 Redis 가 데이터를 잃지 않았다면 예전 값(DB 모드 판매가 반영되지 않은 큰 값)이 남아 있고,
     * 이 시점에는 상품 행 잠금 · DB 모드 · 대기 시간 덕에 그 키를 정당하게 쓰는 쪽이 없다
     * Redis 쓰기가 실패하면 예외로 롤백돼 DB 모드로 남는다
     */
    @Transactional
    public boolean returnToRedis(UUID productId, long minDwellMs) {
        if (ledger.lockModeExclusive(productId) != StockMode.DB || !ledger.isDbModePastDwell(productId, minDwellMs)) {
            return false;
        }
        redis.initStock(productId, ledger.stockOf(productId) - ledger.sumUnapplied(productId));
        ledger.setModeRedis(productId);
        log.warn("[STOCK_MODE_RETURN] DB -> REDIS - ProductId: {}", productId);
        return true;
    }
}
