package groom.backend.application.payment;

import groom.backend.application.cart.CartApplicationService;
import groom.backend.application.cart.CartApplicationService.CartItemToRemove;
import groom.backend.application.notification.NotificationApplicationService;
import groom.backend.domain.order.model.Order;
import groom.backend.domain.order.model.OrderItem;
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
    private final CartApplicationService cartApplicationService;

    /**
     * 재고 차감 결과를 담는 내부 클래스
     */
    public static class StockReductionResult {
        private final UUID productId;
        private final Integer stockAfter;

        public StockReductionResult(UUID productId, Integer stockAfter) {
            this.productId = productId;
            this.stockAfter = stockAfter;
        }

        public UUID getProductId() {
            return productId;
        }

        public Integer getStockAfter() {
            return stockAfter;
        }
    }

    /**
     * 재고 차감된 상품에 대한 알림을 비동기로 처리 - notificationExecutor 스레드풀에서 실행 - 팀원이 만든 알림 메서드는 내부적으로 트랜잭션 처리
     *
     * @param stockReductions 재고 차감 결과 목록 (제품 ID와 차감 후 재고량 포함)
     */
    @Async("notificationExecutor")
    public void sendStockReducedNotifications(List<StockReductionResult> stockReductions) {
        log.info("[NOTIFICATION_ASYNC_START] Sending stock reduced notifications - Count: {}", stockReductions.size());

        try {
            // 제품 ID와 차감 후 재고량 맵 생성
            Map<UUID, Integer> productStockMap = new HashMap<>();
            for (StockReductionResult result : stockReductions) {
                productStockMap.put(result.getProductId(), result.getStockAfter());
                log.info("[NOTIFICATION_STOCK_MAP] productId={}, stockAfter={}", 
                        result.getProductId(), result.getStockAfter());
            }

            // 차감 후 재고량을 함께 전달하여 정확한 값이 알림에 표시되도록 함
            notificationApplicationService.createAndSendNotificationsForProducts(
                    stockReductions.stream().map(StockReductionResult::getProductId).collect(Collectors.toList()),
                    productStockMap
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

    /**
     * 결제 완료 후 장바구니를 비동기로 비우는 메서드 - notificationExecutor 스레드풀에서 실행 - 장바구니 비우기가 실패해도 결제 응답에 영향 없음 - Order의 OrderItem들을
     * 기반으로 장바구니에서 해당 상품들을 제거
     *
     * @param order 결제 완료된 주문
     */
    @Async("notificationExecutor")
    public void clearCartItems(Order order) {
        Long userId = order.getUserId();
        List<OrderItem> orderItems = order.getOrderItems();

        log.info("[CART_CLEAR_ASYNC_START] Clearing cart items - UserId: {}, OrderId: {}, ItemCount: {}",
                userId, order.getId(), orderItems.size());

        try {
            // OrderItem을 CartItemToRemove로 변환
            List<CartItemToRemove> itemsToRemove = orderItems.stream()
                    .map(orderItem -> new CartItemToRemove(
                            orderItem.getProductId(),
                            orderItem.getQuantity()
                    ))
                    .collect(Collectors.toList());

            // 장바구니에서 제거
            cartApplicationService.removeCartItems(userId, itemsToRemove);

            log.info(
                    "[CART_CLEAR_ASYNC_SUCCESS] Cart items cleared successfully - UserId: {}, OrderId: {}, ItemCount: {}",
                    userId, order.getId(), itemsToRemove.size());

        } catch (Exception e) {
            // 장바구니 비우기 실패는 로그만 남기고 예외를 전파하지 않음
            // 결제는 이미 성공했으므로 장바구니 비우기 실패가 영향을 주면 안됨
            log.error("[CART_CLEAR_ASYNC_FAILED] Failed to clear cart items - UserId: {}, OrderId: {}, Error: {}",
                    userId, order.getId(), e.getMessage(), e);
        }
    }
}
