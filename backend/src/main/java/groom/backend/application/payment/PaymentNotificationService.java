package groom.backend.application.payment;

import groom.backend.application.notification.NotificationApplicationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 결제 완료 후 알림 처리를 비동기로 수행하는 서비스 - @Async를 사용하여 별도 스레드에서 실행 - 알림 처리가 실패해도 결제 응답에 영향 없음
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentNotificationService {
    private final NotificationApplicationService notificationApplicationService;

    /**
     * 재고 차감 결과를 담는 내부 클래스
     */
    public static class StockReductionResult {
        private final UUID productId;
        private final Integer stockAfter;
        private final int quantity;

        public StockReductionResult(UUID productId, Integer stockAfter, int quantity) {
            this.productId = productId;
            this.stockAfter = stockAfter;
            this.quantity = quantity;
        }

        public UUID getProductId() {
            return productId;
        }

        public Integer getStockAfter() {
            return stockAfter;
        }

        // 조건부 UPDATE 한 문장이 차감하므로 차감 전 재고는 차감 후 재고 + 수량이다
        public Integer getStockBefore() {
            return stockAfter + quantity;
        }
    }

    /**
     * 재고 차감된 상품에 대한 알림을 비동기로 처리 - notificationExecutor 스레드풀에서 실행 - 팀원이 만든 알림 메서드는 내부적으로 트랜잭션 처리
     *
     * @param stockReductions 재고 차감 결과 목록 (제품 ID · 차감 후 재고량 · 차감 수량 - 수량으로 구한 차감 전 재고와 차감 후 재고가 임계값을 넘겼는지 가린다)
     * @param orderUserId 주문한 사용자 id (알림에서 제외). 엔티티 대신 값으로 받는다 - 이 메서드는 트랜잭션이 끝난 뒤 다른
     *                    스레드에서 돌고 open-in-view 도 꺼져 있어, Order 의 지연 로딩 필드를 읽으면 LazyInitializationException 이다
     */
    @Async("notificationExecutor")
    public void sendStockReducedNotifications(List<StockReductionResult> stockReductions, Long orderUserId) {
        log.info("[NOTIFICATION_ASYNC_START] Sending stock reduced notifications - Count: {}, orderUserId={}", 
                stockReductions.size(), orderUserId);

        try {
            // 제품 ID와 차감 후 재고량 맵 생성
            Map<UUID, Integer> productStockMap = new HashMap<>();
            Map<UUID, Integer> stockBeforeMap = new HashMap<>();
            for (StockReductionResult result : stockReductions) {
                productStockMap.put(result.getProductId(), result.getStockAfter());
                stockBeforeMap.put(result.getProductId(), result.getStockBefore());
                log.info("[NOTIFICATION_STOCK_MAP] productId={}, stockBefore={}, stockAfter={}",
                        result.getProductId(), result.getStockBefore(), result.getStockAfter());
            }

            // 차감 전 · 후 재고량을 함께 전달해 임계값을 넘긴 차감만 알리고, 알림에는 차감 후 값이 표시되도록 함
            // 주문한 사용자는 알림에서 제외
            notificationApplicationService.createAndSendNotificationsForProducts(
                    stockReductions.stream().map(StockReductionResult::getProductId).collect(Collectors.toList()),
                    productStockMap,
                    stockBeforeMap,
                    orderUserId
            );

            log.info("[NOTIFICATION_PROCESSING] Processing notifications for {} products", stockReductions.size());

            log.info("[NOTIFICATION_ASYNC_SUCCESS] Stock reduced notifications sent successfully - Count: {}",
                    stockReductions.size());

        } catch (Exception e) {
            log.error(
                    "[NOTIFICATION_ASYNC_FAILED] Failed to send stock reduced notifications - Count: {}, Error: {}",
                    stockReductions.size(), e.getMessage(), e);
        }
    }
}
