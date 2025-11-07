package groom.backend.application.payment;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 결제 완료 후 알림 처리를 비동기로 수행하는 서비스
 * - @Async를 사용하여 별도 스레드에서 실행
 * - 알림 처리가 실패해도 결제 응답에 영향 없음
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentNotificationService {

    // TODO: 팀원이 만든 알림 서비스를 주입받아 사용
    // private final YourNotificationService notificationService;

    /**
     * 재고 차감된 상품에 대한 알림을 비동기로 처리
     * - notificationExecutor 스레드풀에서 실행
     * - 팀원이 만든 알림 메서드는 내부적으로 트랜잭션 처리
     *
     * @param productIds 재고가 차감된 상품 ID 목록
     */
    @Async("notificationExecutor")
    public void sendStockReducedNotifications(List<UUID> productIds) {
        log.info("[NOTIFICATION_ASYNC_START] Sending stock reduced notifications - ProductIds: {}", productIds);

        try {
            // TODO: 팀원이 만든 알림 메서드 호출
            // 예시: notificationService.sendStockThresholdNotifications(productIds);

            // 임시: 비동기 처리 시뮬레이션
            log.info("[NOTIFICATION_PROCESSING] Processing notifications for {} products", productIds.size());

            // 팀원의 메서드가 준비되면 아래 주석을 해제하고 위 로그를 삭제
            // notificationService.sendStockThresholdNotifications(productIds);

            log.info("[NOTIFICATION_ASYNC_SUCCESS] Stock reduced notifications sent successfully - ProductIds: {}", productIds);

        } catch (Exception e) {
            // 알림 실패는 로그만 남기고 예외를 전파하지 않음
            // 결제는 이미 성공했으므로 알림 실패가 영향을 주면 안됨
            log.error("[NOTIFICATION_ASYNC_FAILED] Failed to send stock reduced notifications - ProductIds: {}, Error: {}",
                    productIds, e.getMessage(), e);
        }
    }
}
