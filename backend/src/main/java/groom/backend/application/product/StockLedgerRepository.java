package groom.backend.application.product;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.product.model.enums.StockMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 재고 원장(stock_ledger)과 플래시 세일 상품 행의 네이티브 SQL 모음.
 * <p>
 * 호출한 트랜잭션에 그대로 합류한다(JdbcTemplate 은 JPA 트랜잭션의 커넥션을 쓴다). 잠금 순서는 한 가지로 고정한다 -
 * 상품 행 잠금이 먼저, 원장 행 잠금이 나중이다. 적용 배치와 전환 트랜잭션이 서로 반대 순서로 잡으면 교착하기 때문이다
 */
@Repository
@RequiredArgsConstructor
public class StockLedgerRepository {

    public enum Kind { SALE, REVERSAL }

    /** 배치가 집은 원장 행. qty 는 SALE 양수 · REVERSAL 음수 */
    public record LedgerRow(long id, int qty) {
    }

    /** 방금 되돌려진 판매. quantity 는 양수 */
    public record Reversed(UUID productId, int quantity) {
    }

    /** 워밍업이 Redis 에 적재할 값. remaining = stock − 미적용 원장 합 */
    public record ProductRemaining(UUID id, int remaining) {
    }

    private static final String VERSION_BUMP = "version = COALESCE(version, 0) + 1";

    private final JdbcTemplate jdbc;

    // 이 중 플래시 세일 상품의 id 만 돌려준다
    public List<UUID> findFlashProductIds(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("SELECT id FROM product WHERE flash_sale = true AND id IN (" + placeholders(productIds.size()) + ")",
                UUID.class, productIds.toArray());
    }

    // 잠금 없는 읽기 - 결제 진입이 Redis 를 쓸지 정하는 데 쓴다
    public StockMode readMode(UUID productId) {
        return modeOf(jdbc.queryForList("SELECT stock_mode FROM product WHERE id = ?", String.class, productId), productId);
    }

    // TX1 이 읽는 동안 전환 트랜잭션(FOR UPDATE)이 끼어들지 못하게 공유 잠금으로 읽는다
    public StockMode lockModeShared(UUID productId) {
        return modeOf(jdbc.queryForList("SELECT stock_mode FROM product WHERE id = ? FOR SHARE", String.class, productId), productId);
    }

    // 전환 · 복귀 트랜잭션의 상품 행 잠금
    public StockMode lockModeExclusive(UUID productId) {
        return modeOf(jdbc.queryForList("SELECT stock_mode FROM product WHERE id = ? FOR UPDATE", String.class, productId), productId);
    }

    // DB 모드의 재고 판정. 영향 행 0 = 재고 부족이거나 모드가 DB 가 아님
    // version 을 올려 상품 엔티티를 들고 있던 수정 저장이 오래된 stock 으로 덮어쓰지 못하게 한다
    public int decreaseInDbModeIfEnough(UUID productId, int quantity) {
        return jdbc.update("UPDATE product SET stock = stock - ?, " + VERSION_BUMP
                + " WHERE id = ? AND stock >= ? AND stock_mode = 'DB'", quantity, productId, quantity);
    }

    // 같은 (결제, 상품, 종류) 가 이미 있으면 아무것도 쓰지 않고 false 다
    public boolean insertLedger(UUID paymentId, UUID productId, int qty, Kind kind, boolean applied) {
        return jdbc.update("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied, applied_at) "
                        + "VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() END) "
                        + "ON CONFLICT (payment_id, product_id, kind) DO NOTHING",
                paymentId, productId, qty, kind.name(), applied, applied) == 1;
    }

    // 결제의 SALE 행마다 부호를 뒤집은 REVERSAL 을 쓴다. 이미 쓴 상품은 건너뛰므로 두 번 불러도 행은 하나다. 새로 쓴 것만 돌려준다
    public List<Reversed> insertReversals(UUID paymentId) {
        return jdbc.query("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) "
                        + "SELECT payment_id, product_id, -qty, 'REVERSAL', false FROM stock_ledger "
                        + "WHERE payment_id = ? AND kind = 'SALE' "
                        + "ON CONFLICT (payment_id, product_id, kind) DO NOTHING RETURNING product_id, -qty",
                (rs, i) -> new Reversed(rs.getObject(1, UUID.class), rs.getInt(2)), paymentId);
    }

    // 한 상품의 SALE 만 되돌린다 - Toss 승인 전에 실패한 요청이 자기가 DB 모드로 예약한 항목만 되돌릴 때 쓴다
    public List<Reversed> insertReversal(UUID paymentId, UUID productId) {
        return jdbc.query("INSERT INTO stock_ledger (payment_id, product_id, qty, kind, applied) "
                        + "SELECT payment_id, product_id, -qty, 'REVERSAL', false FROM stock_ledger "
                        + "WHERE payment_id = ? AND product_id = ? AND kind = 'SALE' "
                        + "ON CONFLICT (payment_id, product_id, kind) DO NOTHING RETURNING product_id, -qty",
                (rs, i) -> new Reversed(rs.getObject(1, UUID.class), rs.getInt(2)), paymentId, productId);
    }

    // 후처리가 이 결제에서 원장이 이미 판매를 들고 있는 상품을 가려내는 데 쓴다
    public Set<UUID> findSaleProductIds(UUID paymentId) {
        return Set.copyOf(jdbc.queryForList(
                "SELECT product_id FROM stock_ledger WHERE payment_id = ? AND kind = 'SALE'", UUID.class, paymentId));
    }

    public int sumUnapplied(UUID productId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM stock_ledger WHERE product_id = ? AND applied = false",
                Integer.class, productId);
    }

    // 상품별 미적용 합. 행이 없는 상품은 담기지 않는다
    public Map<UUID, Integer> sumUnappliedByProduct(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> sums = new HashMap<>();
        jdbc.query("SELECT product_id, SUM(qty) FROM stock_ledger WHERE applied = false AND product_id IN ("
                        + placeholders(productIds.size()) + ") GROUP BY product_id",
                rs -> {
                    sums.put(rs.getObject(1, UUID.class), rs.getInt(2));
                }, productIds.toArray());
        return sums;
    }

    public int stockOf(UUID productId) {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId);
    }

    public List<UUID> findProductIdsWithUnapplied() {
        return jdbc.queryForList("SELECT DISTINCT product_id FROM stock_ledger WHERE applied = false", UUID.class);
    }

    // 다른 인스턴스가 이 상품을 처리 중이면 false - 그 상품은 이번 회차에서 건너뛴다
    public boolean lockProductSkipLocked(UUID productId) {
        return !jdbc.queryForList("SELECT 1 FROM product WHERE id = ? FOR UPDATE SKIP LOCKED", Integer.class, productId).isEmpty();
    }

    // 상품 행을 이미 잠근 트랜잭션 안에서 미적용 행을 집는다
    public List<LedgerRow> claimUnapplied(UUID productId) {
        return jdbc.query("SELECT id, qty FROM stock_ledger WHERE product_id = ? AND applied = false ORDER BY id FOR UPDATE SKIP LOCKED",
                (rs, i) -> new LedgerRow(rs.getLong(1), rs.getInt(2)), productId);
    }

    // delta 는 접어 넣는 부호 있는 합. 음수면 재고가 늘어난다
    public int lowerStock(UUID productId, int delta) {
        return jdbc.update("UPDATE product SET stock = stock - ?, " + VERSION_BUMP + " WHERE id = ?", delta, productId);
    }

    public int markApplied(List<Long> ledgerIds) {
        return jdbc.update("UPDATE stock_ledger SET applied = true, applied_at = now() WHERE id IN ("
                + placeholders(ledgerIds.size()) + ")", ledgerIds.toArray());
    }

    public void setModeDb(UUID productId) {
        jdbc.update("UPDATE product SET stock_mode = 'DB', stock_mode_changed_at = now(), " + VERSION_BUMP + " WHERE id = ?", productId);
    }

    public void setModeRedis(UUID productId) {
        jdbc.update("UPDATE product SET stock_mode = 'REDIS', stock_mode_changed_at = now(), " + VERSION_BUMP + " WHERE id = ?", productId);
    }

    // DB 모드로 minDwellMs 이상 머문 플래시 상품
    public List<UUID> findDbModeProductsPastDwell(long minDwellMs) {
        return jdbc.queryForList("SELECT id FROM product WHERE flash_sale = true AND stock_mode = 'DB' "
                + "AND stock_mode_changed_at <= now() - (? * interval '1 millisecond')", UUID.class, minDwellMs);
    }

    // 상품 행을 잠근 뒤 대기 시간을 다시 확인하는 데 쓴다
    public boolean isDbModePastDwell(UUID productId, long minDwellMs) {
        return !jdbc.queryForList("SELECT 1 FROM product WHERE id = ? AND stock_mode = 'DB' "
                + "AND stock_mode_changed_at <= now() - (? * interval '1 millisecond')", Integer.class, productId, minDwellMs).isEmpty();
    }

    // 워밍업용 키셋 페이지. REDIS 모드 플래시 상품만, 값은 stock − 미적용 합이다
    public List<ProductRemaining> findFlashRemainingAfter(UUID lastId, int limit) {
        return jdbc.query("SELECT p.id, p.stock - COALESCE((SELECT SUM(l.qty) FROM stock_ledger l "
                        + "WHERE l.product_id = p.id AND l.applied = false), 0) "
                        + "FROM product p WHERE p.flash_sale = true AND p.stock_mode = 'REDIS' AND p.stock IS NOT NULL AND p.id > ? "
                        + "ORDER BY p.id LIMIT ?",
                (rs, i) -> new ProductRemaining(rs.getObject(1, UUID.class), rs.getInt(2)), lastId, limit);
    }

    private static StockMode modeOf(List<String> rows, UUID productId) {
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return StockMode.valueOf(rows.get(0));
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
