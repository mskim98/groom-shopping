package groom.backend.application.payment;

import groom.backend.application.payment.event.PaymentApprovedEvent.ReservedItem;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockLedgerRepository;
import groom.backend.application.product.StockLedgerService;
import groom.backend.application.product.StockLedgerService.DbReservation;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.order.model.OrderItem;
import groom.backend.domain.product.model.enums.StockMode;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 플래시 세일 상품의 결제 흐름 재고 처리: 진입 예약 → TX1 원장 기록 → 실패 시 되돌림.
 * <p>
 * 진입은 상품의 stock_mode 를 잠금 없이 먼저 읽는다. REDIS 모드일 때만 Redis 선점을 하고(redis-stock 서킷 안에서),
 * Redis 가 예외 · 서킷 OPEN · 키 없음(-2)이면 상품을 DB 모드로 전환해 조건부 UPDATE 로 예약한다. DB 모드면 Redis 를 건드리지 않는다
 * 복귀 직후 오래된 키로 선점한 요청이 새 키를 모르는 미적용 SALE 을 남기는 일을 막기 위해서다.
 * 플래시가 아닌 상품은 Redis 도 원장도 쓰지 않는다(컨슈머가 조건부 UPDATE 로 낮춘다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlashStockReservation {

    private static final String CIRCUIT = "redis-stock";
    private static final long REDIS_UNAVAILABLE = -3L;

    private final StockLedgerRepository ledger;
    private final StockLedgerService ledgerService;
    private final ProductStockRedisRepository redis;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * 주문의 플래시 세일 항목을 예약한다. 하나라도 모자라면 이미 예약한 항목을 되돌리고 INSUFFICIENT_STOCK 으로 끝낸다
     *
     * @return 이 요청이 실제로 예약한 항목(플래시가 아닌 항목 · 먼저 온 요청이 예약한 항목은 담기지 않는다)
     */
    public List<ReservedItem> reserve(UUID paymentId, List<OrderItem> orderItems) {
        Map<UUID, Integer> quantities = quantitiesByProduct(orderItems);
        List<UUID> flashIds = ledger.findFlashProductIds(quantities.keySet());
        List<ReservedItem> reserved = new ArrayList<>();
        for (UUID productId : flashIds) {
            try {
                reserveOne(paymentId, productId, quantities.get(productId)).ifPresent(reserved::add);
            } catch (RuntimeException e) {
                releaseBeforeRecord(paymentId, reserved);
                throw e;
            }
        }
        return reserved;
    }

    // 같은 상품이 두 항목이어도 (결제, 상품, 종류) 유일 제약에 맞게 한 건으로 합친다
    private static Map<UUID, Integer> quantitiesByProduct(List<OrderItem> orderItems) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (OrderItem item : orderItems) {
            quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        return quantities;
    }

    private Optional<ReservedItem> reserveOne(UUID paymentId, UUID productId, int quantity) {
        // 두 번째 시도는 DB 모드 예약 중 REDIS 로 복귀한 드문 경우다
        for (int attempt = 0; attempt < 2; attempt++) {
            if (ledger.readMode(productId) == StockMode.REDIS) {
                long result = reserveInRedis(productId, quantity);
                if (result >= 0) {
                    return Optional.of(new ReservedItem(productId, quantity, StockMode.REDIS));
                }
                if (result == -1L) {
                    log.info("[STOCK_RESERVE_OUT] ProductId: {}, Quantity: {}", productId, quantity);
                    throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
                }
                // 키 없음(-2) 또는 Redis 사용 불가 - 재고 검사를 건너뛰지 않고 DB 모드로 넘어간다
                ledgerService.switchToDb(productId);
            }
            DbReservation result = ledgerService.reserveInDb(paymentId, productId, quantity);
            switch (result) {
                case RESERVED -> {
                    return Optional.of(new ReservedItem(productId, quantity, StockMode.DB));
                }
                case DUPLICATE -> {
                    return Optional.empty();
                }
                case SHORT -> {
                    log.info("[STOCK_RESERVE_OUT] DB mode - ProductId: {}, Quantity: {}", productId, quantity);
                    throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
                }
                case MODE_FLIPPED -> {
                    // 다음 시도에서 Redis 로
                }
            }
        }
        throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
    }

    private long reserveInRedis(UUID productId, int quantity) {
        try {
            return circuitBreakerRegistry.circuitBreaker(CIRCUIT).executeSupplier(() -> redis.preReserve(productId, quantity));
        } catch (RuntimeException e) {
            // 서킷 OPEN(CallNotPermittedException) 도 여기로 온다
            log.warn("[STOCK_REDIS_UNAVAILABLE] DB 모드로 전환 - ProductId: {}, Cause: {}", productId, e.toString());
            return REDIS_UNAVAILABLE;
        }
    }

    /** 테스트 확정처럼 예약 단계가 없는 경로에서, 주문의 플래시 항목을 REDIS 예약으로 간주해 돌려준다 */
    public List<ReservedItem> flashItemsOf(List<OrderItem> orderItems) {
        Map<UUID, Integer> quantities = quantitiesByProduct(orderItems);
        return ledger.findFlashProductIds(quantities.keySet()).stream()
                .map(id -> new ReservedItem(id, quantities.get(id), StockMode.REDIS))
                .toList();
    }

    /**
     * TX1 에서 호출한다(호출한 트랜잭션에 합류). Redis 로 선점한 항목마다 원장 기록을 남긴다. DB 모드로 예약한 항목은 SALE 이 이미 있어 건너뛴다
     * 상품 id 오름차순으로 처리해 여러 상품 주문 둘이 서로의 잠금을 기다리지 않게 한다
     */
    public void recordSales(UUID paymentId, List<ReservedItem> items) {
        items.stream()
                .filter(item -> item.mode() == StockMode.REDIS)
                .sorted(Comparator.comparing(ReservedItem::productId))
                .forEach(item -> ledgerService.recordSale(paymentId, item.productId(), item.quantity()));
    }

    /**
     * 승인 트랜잭션(TX1) 전에 실패했을 때: 이 요청이 DB 모드로 예약한 항목은 REVERSAL 로, Redis 로 선점한 항목은 Redis 복원으로 되돌린다
     * 실패해도 던지지 않는다 - 되돌림 실패가 보상 기록 · FAILED 기록 · 원래 예외 전파를 막으면 안 된다
     */
    public void releaseBeforeRecord(UUID paymentId, List<ReservedItem> items) {
        for (ReservedItem item : items) {
            try {
                if (item.mode() == StockMode.DB) {
                    ledger.insertReversal(paymentId, item.productId());
                } else {
                    releaseRedis(item.productId(), item.quantity());
                }
            } catch (Exception e) {
                log.error("[STOCK_RELEASE_FAILED] 선점 되돌림 실패 - PaymentId: {}, ProductId: {}, Error: {}",
                        paymentId, item.productId(), e.getMessage());
            }
        }
    }

    /**
     * TX1 이 커밋된 뒤 실패했을 때(컨슈머 후처리 실패 · 대사 포기): 결제의 SALE 행 전부를 REVERSAL 로 되돌린다. 이미 쓴 행은 건너뛰므로 두 번 불러도 하나다
     * Redis 는 이번에 REVERSAL 이 새로 쓰인 REDIS 예약 항목만, 모드가 아직 REDIS 일 때 복원한다
     * items 가 비어 있어도(outbox 가 없는 대사) 원장 되돌림은 한다
     */
    public void releaseAfterRecord(UUID paymentId, List<ReservedItem> items) {
        List<StockLedgerRepository.Reversed> toRelease;
        try {
            toRelease = reverseSales(paymentId, items);
        } catch (Exception e) {
            log.error("[STOCK_REVERSAL_FAILED] REVERSAL 기록 실패(수동 대사 필요) - PaymentId: {}, Error: {}", paymentId, e.getMessage());
            return;
        }
        releaseRedis(toRelease);
    }

    /**
     * 결제의 SALE 행 전부에 REVERSAL 을 쓰고(호출한 트랜잭션에 합류), 이번에 새로 쓰인 REDIS 예약 항목의 Redis 복원 대상을 돌려준다
     * 사용자 취소처럼 REVERSAL 이 커밋되는 트랜잭션 안에서 부르는 경로가 Redis 복원을 커밋 뒤로 미룰 수 있게 둘로 나눴다 - 롤백된 REVERSAL 에 Redis 만 복원되면 키가 실제보다 커진다
     */
    public List<StockLedgerRepository.Reversed> reverseSales(UUID paymentId, List<ReservedItem> items) {
        Map<UUID, ReservedItem> byProduct = new LinkedHashMap<>();
        items.forEach(item -> byProduct.put(item.productId(), item));
        return ledger.insertReversals(paymentId).stream()
                .filter(row -> byProduct.get(row.productId()) != null && byProduct.get(row.productId()).mode() == StockMode.REDIS)
                .toList();
    }

    /** {@link #reverseSales} 가 돌려준 항목을 Redis 에 복원한다(모드가 REDIS 일 때만, 실패는 삼킨다) */
    public void releaseRedis(List<StockLedgerRepository.Reversed> rows) {
        rows.forEach(row -> releaseRedis(row.productId(), row.quantity()));
    }

    /**
     * 같은 결제를 먼저 처리한 요청이 있어 이 요청의 예약이 중복일 때: 이 요청이 Redis 에서 선점한 수량만 돌려준다
     * DB 모드 예약은 먼저 온 요청의 SALE 이 이미 있어 이 요청에는 없다(DUPLICATE)
     */
    public void releaseRedisOnly(List<ReservedItem> items) {
        for (ReservedItem item : items) {
            if (item.mode() == StockMode.REDIS) {
                releaseRedis(item.productId(), item.quantity());
            }
        }
    }

    // 모드가 REDIS 일 때만 복원한다. DB 모드의 키는 오래된 값이라 복귀가 덮어쓰며, 키가 없으면 Lua 가 만들지 않는다
    private void releaseRedis(UUID productId, int quantity) {
        try {
            if (ledger.readMode(productId) == StockMode.REDIS) {
                redis.release(productId, quantity);
            }
        } catch (Exception e) {
            log.error("[STOCK_RELEASE_FAILED] Redis 선점 복원 실패 - ProductId: {}, Quantity: {}, Error: {}",
                    productId, quantity, e.getMessage());
        }
    }
}
