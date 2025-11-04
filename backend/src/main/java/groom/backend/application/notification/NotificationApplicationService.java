package groom.backend.application.notification;

import groom.backend.domain.notification.entity.Notification;
import groom.backend.domain.notification.repository.NotificationRepository;
import groom.backend.infrastructure.sse.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 알림 생성 및 SSE 전송을 담당하는 Application Service입니다.
 * Kafka Consumer에서 호출되어 재고 임계값 알림을 생성하고 실시간으로 전송합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationApplicationService {

    private final NotificationRepository notificationRepository;
    private final SseService sseService;

    /**
     * 재고 임계값 도달 시 해당 제품을 장바구니에 담은 모든 사용자에게 알림을 생성하고 SSE로 전송합니다.
     * 성능 측정을 위해 처리 시간을 로깅합니다.
     *
     * @param productId 제품 ID
     * @param currentStock 현재 재고
     * @param thresholdValue 임계값
     */
    @Transactional
    public void createAndSendNotifications(UUID productId, Integer currentStock, Integer thresholdValue) {
        long startTime = System.currentTimeMillis();
        log.info("[NOTIFICATION_SERVICE_START] productId={}, currentStock={}, thresholdValue={}", 
                productId, currentStock, thresholdValue);

        try {
            // 1. 해당 제품을 장바구니에 담은 사용자 조회
            long queryStartTime = System.currentTimeMillis();
            List<Long> userIds = notificationRepository.findUserIdsWithProductInCart(productId);
            long queryDuration = System.currentTimeMillis() - queryStartTime;
            log.info("[NOTIFICATION_QUERY_USERS] productId={}, userIdCount={}, queryDuration={}ms", 
                    productId, userIds.size(), queryDuration);

            if (userIds.isEmpty()) {
                log.info("[NOTIFICATION_NO_USERS] productId={}", productId);
                return;
            }

            // 2. 각 사용자에게 알림 생성 및 SSE 전송
            int successCount = 0;
            int failCount = 0;
            long totalUserProcessingTime = 0;

            for (Long userId : userIds) {
                try {
                    long userStartTime = System.currentTimeMillis();
                    
                    // 알림 생성
                    Notification notification = Notification.create(userId, productId, currentStock, thresholdValue);
                    Notification saved = notificationRepository.save(notification);
                    
                    // SSE로 실시간 전송
                    sseService.sendNotification(userId, saved);
                    
                    long userEndTime = System.currentTimeMillis();
                    long userDuration = userEndTime - userStartTime;
                    totalUserProcessingTime += userDuration;
                    
                    log.info("[NOTIFICATION_SENT] userId={}, notificationId={}, duration={}ms", 
                            userId, saved.getId(), userDuration);
                    successCount++;
                    
                } catch (Exception e) {
                    log.error("[NOTIFICATION_SEND_FAILED] userId={}, productId={}, error={}", 
                            userId, productId, e.getMessage(), e);
                    failCount++;
                }
            }

            long serviceEndTime = System.currentTimeMillis();
            long totalServiceDuration = serviceEndTime - startTime;
            long avgUserProcessingTime = userIds.size() > 0 ? totalUserProcessingTime / userIds.size() : 0;
            
            log.info("[NOTIFICATION_SERVICE_COMPLETE] productId={}, totalUsers={}, successCount={}, failCount={}, totalDuration={}ms, avgUserProcessingTime={}ms", 
                    productId, userIds.size(), successCount, failCount, totalServiceDuration, avgUserProcessingTime);
            
        } catch (Exception e) {
            long errorDuration = System.currentTimeMillis() - startTime;
            log.error("[NOTIFICATION_SERVICE_FAILED] productId={}, duration={}ms, error={}", 
                    productId, errorDuration, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 사용자의 모든 알림을 조회합니다.
     */
    @Transactional(readOnly = true)
    public List<Notification> getNotifications(Long userId) {
        return notificationRepository.findByUserId(userId);
    }

    /**
     * 사용자의 읽지 않은 알림을 조회합니다.
     */
    @Transactional(readOnly = true)
    public List<Notification> getUnreadNotifications(Long userId) {
        return notificationRepository.findUnreadByUserId(userId);
    }

    /**
     * 알림을 읽음 처리합니다.
     */
    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        notificationRepository.findById(notificationId)
                .filter(notification -> notification.getUserId().equals(userId))
                .ifPresent(notification -> {
                    notification.markAsRead();
                    notificationRepository.save(notification);
                    log.info("[NOTIFICATION_MARK_READ] notificationId={}, userId={}", notificationId, userId);
                });
    }

    /**
     * 사용자의 모든 알림을 읽음 처리합니다.
     */
    @Transactional
    public void markAllAsRead(Long userId) {
        List<Notification> unreadNotifications = notificationRepository.findUnreadByUserId(userId);
        int count = 0;
        for (Notification notification : unreadNotifications) {
            notification.markAsRead();
            notificationRepository.save(notification);
            count++;
        }
        log.info("[NOTIFICATION_MARK_ALL_READ] userId={}, count={}", userId, count);
    }

    /**
     * 알림을 삭제합니다.
     */
    @Transactional
    public void deleteNotification(Long notificationId, Long userId) {
        notificationRepository.findById(notificationId)
                .filter(notification -> notification.getUserId().equals(userId))
                .ifPresent(notification -> {
                    notificationRepository.deleteById(notificationId);
                    log.info("[NOTIFICATION_DELETE] notificationId={}, userId={}", notificationId, userId);
                });
    }

    /**
     * 여러 알림을 일괄 삭제합니다.
     */
    @Transactional
    public void deleteNotifications(List<Long> notificationIds, Long userId) {
        List<Notification> notifications = notificationRepository.findByIds(notificationIds);
        List<Long> validIds = notifications.stream()
                .filter(notification -> notification.getUserId().equals(userId))
                .map(Notification::getId)
                .toList();
        
        if (!validIds.isEmpty()) {
            notificationRepository.deleteAllById(validIds);
            log.info("[NOTIFICATION_BATCH_DELETE] userId={}, deletedCount={}, requestedCount={}", 
                    userId, validIds.size(), notificationIds.size());
        }
    }
}

