package groom.backend.application.product;

import groom.backend.application.product.StockLedgerRepository.LedgerRow;
import groom.backend.application.product.StockLedgerRepository.Reversed;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.product.model.enums.StockMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 플래시 세일 재고 원장의 트랜잭션 단위 작업. 상품 하나를 한 트랜잭션으로 다룬다.
 * <p>
 * 잠금 순서는 advisory → 상품 행 → 원장 행이다. 결제 경로(TX1 · DB 모드 진입)는 공유 advisory 만 잡고 REDIS 모드에서 상품 행을 잠그지 않는다.
 * 전환 · 복귀는 배타 advisory 로 진행 중인 TX1 을 기다린 뒤(lock-timeout-ms 까지) 상품 행을 잠근다. REVERSAL 기록(reverseSales)도 공유 advisory 아래에서 모드를 읽는다. 적용 배치는 advisory 를 잡지 않는다 -
 * 배치가 도는 동안 들어온 미적용 행은 다음 회차에 적용되고, 남은 재고 = product.stock − SUM(미적용 qty) 은 stock 변경과 적용 표시를
 * 한 트랜잭션으로 커밋하므로 어느 순간에도 성립한다
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

    /**
     * REVERSAL 을 쓴 결과. written 은 이번에 새로 쓰인 행, modes 는 REVERSAL 을 쓴 같은 트랜잭션이 공유 잠금 아래에서 읽은 상품별 모드다
     * 커밋 뒤 Redis 복원은 modes 가 REDIS 인 상품에만 한다
     */
    public record Reversal(List<Reversed> written, Map<UUID, StockMode> modes) {
    }

    private final StockLedgerRepository ledger;
    private final ProductStockRedisRepository redis;

    // 배타 advisory 잠금 대기 상한. 너무 작으면 TX1 이 조금만 길어도 전환이 실패하고, 너무 크면 열린 TX1 하나가 전환 · 복귀 스레드를 그만큼 붙잡는다
    @Value("${stock.mode.lock-timeout-ms:3000}")
    private long lockTimeoutMs = 3000;

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
        try {
            ledger.setLockTimeout(lockTimeoutMs);
            ledger.lockProductExclusive(productId);
            if (ledger.lockModeExclusive(productId) == StockMode.DB) {
                return;
            }
            foldUnapplied(productId);
            ledger.setModeDb(productId);
        } catch (DataAccessException e) {
            requireLockTimeout(e);
            // 열린 TX1 이 상한보다 오래 잠금을 쥐고 있다. 결제 실패 경로가 이 예약을 되돌리고 사용자는 잠시 뒤 다시 시도한다
            log.warn("[STOCK_MODE_SWITCH_LOCK_TIMEOUT] 전환 포기 - ProductId: {}, LockTimeoutMs: {}", productId, lockTimeoutMs);
            throw new BusinessException(ErrorCode.PRODUCT_STOCK_CONFLICT);
        }
        log.warn("[STOCK_MODE_SWITCH] REDIS -> DB - ProductId: {}", productId);
    }

    /**
     * DB 모드 진입 예약. SALE(applied=true) 행을 먼저 쓰고 조건부 UPDATE 로 재고를 낮춘다. 재고가 모자라면 같은 트랜잭션을 롤백해 행도 없앤다
     * 호출한 쪽은 Toss 호출 전에 이 트랜잭션이 끝난 상태다
     */
    @Transactional
    public DbReservation reserveInDb(UUID paymentId, UUID productId, int quantity) {
        ledger.lockProductShared(productId);
        if (ledger.readMode(productId) == StockMode.REDIS) {
            return DbReservation.MODE_FLIPPED;
        }
        if (!ledger.insertLedger(paymentId, productId, quantity, StockLedgerRepository.Kind.SALE, true)) {
            return DbReservation.DUPLICATE;
        }
        if (ledger.decreaseInDbModeIfEnough(productId, quantity) == 1) {
            return DbReservation.RESERVED;
        }
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return DbReservation.SHORT;
    }

    /**
     * TX1(승인 트랜잭션)의 원장 기록. Redis 로 선점한 항목 하나를 호출한 트랜잭션에 합류해 기록한다.
     * 공유 advisory 잠금을 먼저 잡고(커밋까지 전환 · 복귀를 막는다) 모드를 잠금 없이 읽는다. 잠금을 쥔 동안 모드는 바뀌지 않는다.
     * REDIS → product 행을 건드리지 않고 미적용 SALE 만 쓴다. DB → 조건부 UPDATE + SALE(applied=true), 0행이면 재고 부족이라 던져 보상 경로로 보낸다
     */
    @Transactional
    public void recordSale(UUID paymentId, UUID productId, int quantity) {
        ledger.lockProductShared(productId);
        if (ledger.readMode(productId) == StockMode.REDIS) {
            ledger.insertLedger(paymentId, productId, quantity, StockLedgerRepository.Kind.SALE, false);
            return;
        }
        if (ledger.decreaseInDbModeIfEnough(productId, quantity) == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        if (!ledger.insertLedger(paymentId, productId, quantity, StockLedgerRepository.Kind.SALE, true)) {
            // 이미 SALE 이 있는데 재고를 또 낮췄다 - 롤백해 이중 차감을 막는다
            throw new IllegalStateException("이미 판매가 기록된 결제를 DB 모드로 다시 차감하려 함 - paymentId=" + paymentId);
        }
    }

    /**
     * 복귀. 배타 advisory 잠금으로 진행 중인 TX1 을 기다리고 상품 행을 잠근 뒤 DB 모드와 대기 시간을 다시 확인하고, Redis 키를 stock − 미적용 합으로 덮어쓰고 REDIS 모드로 바꾼다
     * NX 가 아니라 덮어쓰는 이유 - 장애 때 Redis 가 데이터를 잃지 않았다면 예전 값(DB 모드 판매가 반영되지 않은 큰 값)이 남아 있고,
     * 이 시점에는 상품 행 잠금 · DB 모드 · 대기 시간 덕에 그 키를 정당하게 쓰는 쪽이 없다
     * Redis 쓰기가 실패하면 예외로 롤백돼 DB 모드로 남는다
     */
    @Transactional
    public boolean returnToRedis(UUID productId, long minDwellMs) {
        try {
            ledger.setLockTimeout(lockTimeoutMs);
            ledger.lockProductExclusive(productId);
            if (ledger.lockModeExclusive(productId) != StockMode.DB || !ledger.isDbModePastDwell(productId, minDwellMs)) {
                return false;
            }
            redis.initStock(productId, ledger.stockOf(productId) - ledger.sumUnapplied(productId));
            ledger.setModeRedis(productId);
        } catch (DataAccessException e) {
            requireLockTimeout(e);
            // 열린 TX1 이나 취소 트랜잭션이 상한보다 오래 잠금을 쥐고 있다. DB 모드로 남기고 다음 회차에 다시 시도한다
            log.warn("[STOCK_MODE_RETURN_LOCK_TIMEOUT] 이번 회차 건너뜀 - ProductId: {}, LockTimeoutMs: {}", productId, lockTimeoutMs);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
        log.warn("[STOCK_MODE_RETURN] DB -> REDIS - ProductId: {}", productId);
        return true;
    }

    /**
     * REVERSAL 기록. 결제의 SALE 상품마다 공유 advisory 잠금을 잡고 그 트랜잭션 안에서 모드를 읽은 뒤 REVERSAL 을 쓴다
     * 복귀(배타 잠금)는 이 트랜잭션보다 온전히 앞서거나 온전히 뒤에 돈다 -
     * 앞서면 모드는 REDIS 라 호출한 쪽이 커밋 뒤에 Redis 를 복원하고(복귀가 쓴 키는 이 REVERSAL 을 모른다),
     * 뒤서면 모드는 DB 라 복원하지 않는다(복귀가 stock − 미적용 합으로 키를 쓸 때 이 REVERSAL 이 이미 합에 들어 있다)
     * 호출한 트랜잭션이 있으면 합류한다 - 사용자 취소는 취소 트랜잭션 안에서 부른다
     */
    @Transactional
    public Reversal reverseSales(UUID paymentId) {
        Map<UUID, StockMode> modes = ledger.lockSaleProductsShared(paymentId);
        return new Reversal(ledger.insertReversals(paymentId), modes);
    }

    // lock_timeout 초과(SQLSTATE 55P03)가 아닌 오류는 그대로 던진다. 스프링이 55P03 을 CannotAcquireLockException 으로 옮기는 경우와 분류하지 못하는 경우가 모두 있다
    private static void requireLockTimeout(DataAccessException e) {
        boolean timeout = e instanceof CannotAcquireLockException
                || (e instanceof UncategorizedSQLException u && "55P03".equals(u.getSQLException().getSQLState()));
        if (!timeout) {
            throw e;
        }
    }
}
