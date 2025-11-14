package groom.backend.application.notification;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.notification.entity.Notification;
import groom.backend.domain.notification.repository.NotificationRepository;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.infrastructure.sse.SseService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

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
    private final ProductRepository productRepository;
    
    @Qualifier("notificationProcessingExecutor")
    private final ExecutorService executorService;
    
    // 배치 처리 임계값: 사용자 수가 이 값 이상이면 배치 저장 사용
    private static final int BATCH_THRESHOLD = 10;

    /**
     * 재고 임계값 도달 시 해당 제품을 장바구니에 담은 모든 사용자에게 알림을 생성하고 SSE로 전송합니다.
     * 성능 측정을 위해 처리 시간을 로깅합니다.
     *
     * @param productId 제품 ID
     * @param currentStock 현재 재고
     * @param thresholdValue 임계값
     */
    public void createAndSendNotifications(UUID productId, Integer currentStock, Integer thresholdValue) {
        long startTime = System.currentTimeMillis();
        log.info("[NOTIFICATION_SERVICE_START] productId={}, currentStock={}, thresholdValue={}", 
                productId, currentStock, thresholdValue);

        try {
            // 1. 제품 정보 조회 (제품명만 필요, 재고량은 Kafka 이벤트의 currentStock 사용)
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, 
                            "제품을 찾을 수 없습니다: " + productId));
            String productName = product.getName() != null ? product.getName() : "제품";
            

            Integer stockForNotification = currentStock;
            if (stockForNotification == null) {
                // fallback: 제품 조회한 재고량 사용
                stockForNotification = product.getStock();
                log.warn("[NOTIFICATION_STOCK_FALLBACK] productId={}, using product.getStock()={}", 
                        productId, stockForNotification);
            } else {
                log.info("[NOTIFICATION_STOCK_USED] productId={}, kafkaEventStock={}, productStock={}", 
                        productId, stockForNotification, product.getStock());
            }

            // 2. 해당 제품을 장바구니에 담은 사용자 조회
            long queryStartTime = System.currentTimeMillis();
            List<Long> userIds = notificationRepository.findUserIdsWithProductInCart(productId);
            long queryDuration = System.currentTimeMillis() - queryStartTime;
            log.info("[NOTIFICATION_QUERY_USERS] productId={}, productName={}, userIdCount={}, queryDuration={}ms, stockForNotification={}", 
                    productId, productName, userIds.size(), queryDuration, stockForNotification);

            if (userIds.isEmpty()) {
                log.info("[NOTIFICATION_NO_USERS] productId={}, productName={}", productId, productName);
                return;
            }

            // 3. 사용자 수에 따라 배치 저장 또는 개별 저장 선택(메모리 효율을 위해)
            long parallelStartTime = System.currentTimeMillis();
            
            if (userIds.size() >= BATCH_THRESHOLD) {
                // 대량 사용자: 배치 저장 사용 (트랜잭션 그룹화)
                createAndSendNotificationsBatch(userIds, productId, productName, stockForNotification, thresholdValue);
            } else {
                // 소량 사용자: 개별 저장 (기존 방식 유지)
                createAndSendNotificationsIndividual(userIds, productId, productName, stockForNotification, thresholdValue);
            }

            long parallelEndTime = System.currentTimeMillis();
            long parallelDuration = parallelEndTime - parallelStartTime;

            long serviceEndTime = System.currentTimeMillis();
            long totalServiceDuration = serviceEndTime - startTime;
            
            log.info("[NOTIFICATION_SERVICE_COMPLETE] productId={}, totalUsers={}, totalDuration={}ms, parallelDuration={}ms", 
                    productId, userIds.size(), totalServiceDuration, parallelDuration);
            
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

    /**
     * 여러 제품 ID를 받아 재고가 임계값 이하인 제품에 대해 알림을 생성하고 SSE로 전송합니다.
     * 각 제품의 재고가 thresholdValue(기본값 10) 이하인지 확인하고, 조건을 만족하는 제품에 대해서만 알림을 발송합니다.
     *
     * @param productIds 제품 ID 목록
     */
    public void createAndSendNotificationsForProducts(List<UUID> productIds) {
        createAndSendNotificationsForProducts(productIds, null);
    }

    /**
     * 여러 제품 ID를 받아 재고가 임계값 이하인 제품에 대해 알림을 생성하고 SSE로 전송합니다.
     * 차감 후 재고량이 제공되면 그 값을 사용하고, 없으면 제품을 조회해서 사용합니다.
     *
     * @param productIds 제품 ID 목록
     * @param productStockMap 제품 ID와 차감 후 재고량 맵 (null 가능)
     */
    public void createAndSendNotificationsForProducts(List<UUID> productIds, Map<UUID, Integer> productStockMap) {
        long startTime = System.currentTimeMillis();
        log.info("[BATCH_NOTIFICATION_START] productIds={}, count={}, timestamp={}", 
                productIds, productIds.size(), startTime);

        if (productIds == null || productIds.isEmpty()) {
            log.info("[BATCH_NOTIFICATION_EMPTY] no products to check");
            return;
        }

        int processedCount = 0;
        int notifiedCount = 0;
        int skippedCount = 0;

        for (UUID productId : productIds) {
            try {
                long productStartTime = System.currentTimeMillis();
                
                // 1. 제품 정보 조회 (트랜잭션 커밋 후 최신 상태 조회)
                // @Async로 비동기 실행되므로 트랜잭션이 커밋된 후 실행되어야 함
                Product product = productRepository.findById(productId)
                        .orElse(null);

                if (product == null) {
                    log.warn("[BATCH_NOTIFICATION_PRODUCT_NOT_FOUND] productId={}", productId);
                    skippedCount++;
                    continue;
                }

                // 2. 재고와 임계값 확인
                // 차감 후 재고량이 제공되면 그 값을 사용 (결제 경로에서 전달된 정확한 값)
                // 없으면 제품을 조회해서 사용 (다른 경로에서 호출된 경우)
                Integer currentStock;
                if (productStockMap != null && productStockMap.containsKey(productId)) {
                    currentStock = productStockMap.get(productId);
                    log.info("[BATCH_NOTIFICATION_STOCK_FROM_MAP] productId={}, stockFromMap={}, productStock={}", 
                            productId, currentStock, product.getStock());
                } else {
                    currentStock = product.getStock();
                    log.info("[BATCH_NOTIFICATION_STOCK_FROM_DB] productId={}, stockFromDB={}", 
                            productId, currentStock);
                }
                
                Integer thresholdValue = product.getThresholdValue() != null ? product.getThresholdValue() : 10;

                log.info("[BATCH_NOTIFICATION_CHECK] productId={}, productName={}, currentStock={}, thresholdValue={}", 
                        productId, product.getName(), currentStock, thresholdValue);

                // 3. 재고가 임계값 이하인지 확인
                if (currentStock != null && currentStock <= thresholdValue) {
                    // 임계값 이하이면 알림 발송
                    // currentStock은 이미 차감된 값이어야 하므로 그대로 사용
                    log.info("[BATCH_NOTIFICATION_THRESHOLD_REACHED] productId={}, currentStock={}, thresholdValue={}", 
                            productId, currentStock, thresholdValue);
                    
                    // 재고 차감 후 값 사용 (제품 조회 시점의 재고량은 이미 차감된 값)
                    // createAndSendNotifications 내부에서도 currentStock을 그대로 사용하므로
                    // 차감 후 값이 알림 메시지에 표시됨
                    createAndSendNotifications(productId, currentStock, thresholdValue);
                    notifiedCount++;
                } else {
                    log.info("[BATCH_NOTIFICATION_THRESHOLD_NOT_REACHED] productId={}, currentStock={}, thresholdValue={}", 
                            productId, currentStock, thresholdValue);
                    skippedCount++;
                }

                processedCount++;
                long productDuration = System.currentTimeMillis() - productStartTime;
                log.info("[BATCH_NOTIFICATION_PRODUCT_PROCESSED] productId={}, duration={}ms", 
                        productId, productDuration);

            } catch (Exception e) {
                log.error("[BATCH_NOTIFICATION_PRODUCT_FAILED] productId={}, error={}", 
                        productId, e.getMessage(), e);
                skippedCount++;
            }
        }

        long endTime = System.currentTimeMillis();
        long totalDuration = endTime - startTime;

        log.info("[BATCH_NOTIFICATION_COMPLETE] totalProducts={}, processedCount={}, notifiedCount={}, skippedCount={}, totalDuration={}ms", 
                productIds.size(), processedCount, notifiedCount, skippedCount, totalDuration);
    }

    /**
     * 사용자에게 실시간 알림을 전송하고 DB에 저장합니다.
     * 
     * @param userId 사용자 ID
     * @param productId 제품 ID
     * @param message 알림 메시지
     */
    @Transactional
    public void sendRealtimeNotification(Long userId, UUID productId, String message) {
        log.info("[REALTIME_NOTIFICATION_START] userId={}, productId={}, message={}", userId, productId, message);

        try {
            // Notification 객체 생성
            Notification notification = Notification.createForRealtime(userId, productId, message);
            
            // DB에 저장
            Notification saved = notificationRepository.save(notification);
            
            // SSE로 실시간 전송
            sseService.sendNotification(userId, saved);
            
            log.info("[REALTIME_NOTIFICATION_SUCCESS] userId={}, productId={}, notificationId={}, message={}", 
                    userId, productId, saved.getId(), message);
        } catch (Exception e) {
            log.error("[REALTIME_NOTIFICATION_FAILED] userId={}, productId={}, message={}, error={}", 
                    userId, productId, message, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 배치 저장 방식: 대량 사용자 처리 (트랜잭션 그룹화)
     * 메모리 효율을 위해 Chunk 단위로 처리하고 완료를 대기합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void createAndSendNotificationsBatch(
            List<Long> userIds, UUID productId, String productName, 
            Integer currentStock, Integer thresholdValue) {
        
        log.info("[NOTIFICATION_BATCH_START] userIdCount={}, productId={}", userIds.size(), productId);
        
        try {
            // 1. 알림 객체 일괄 생성
            List<Notification> notifications = userIds.stream()
                    .map(userId -> Notification.create(userId, productId, productName, currentStock, thresholdValue))
                    .collect(Collectors.toList());
            
            // 2. 배치 저장 (트랜잭션 그룹화)
            List<Notification> savedNotifications = notificationRepository.saveAll(notifications);
            
            log.info("[NOTIFICATION_BATCH_SAVED] savedCount={}", savedNotifications.size());
            
            // 3. Chunk 단위로 SSE 전송 (메모리 효율)
            // 한 번에 처리할 최대 개수: 100개 (메모리 사용량 제한)
            int chunkSize = 100;
            List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();
            
            for (int i = 0; i < savedNotifications.size(); i += chunkSize) {
                int endIndex = Math.min(i + chunkSize, savedNotifications.size());
                List<Notification> chunk = savedNotifications.subList(i, endIndex);
                
                CompletableFuture<Void> chunkFuture = CompletableFuture.runAsync(() -> {
                    // Chunk 내에서 병렬 처리
                    List<CompletableFuture<Void>> futures = chunk.stream()
                            .map(notification -> CompletableFuture.runAsync(() -> {
                                try {
                                    sseService.sendNotification(notification.getUserId(), notification);
                                    log.debug("[NOTIFICATION_SSE_SENT] userId={}, notificationId={}", 
                                            notification.getUserId(), notification.getId());
                                } catch (Exception e) {
                                    log.error("[NOTIFICATION_SSE_FAILED] userId={}, error={}", 
                                            notification.getUserId(), e.getMessage());
                                }
                            }, executorService))
                            .collect(Collectors.toList());
                    
                    // Chunk 완료 대기 (메모리 효율: 최대 100개의 Future만 유지)
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                }, executorService);
                
                chunkFutures.add(chunkFuture);
            }
            
            // 모든 Chunk 완료 대기 (SSE 전송 완료 보장)
            CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0])).join();
            
            log.info("[NOTIFICATION_BATCH_COMPLETE] userIdCount={}, savedCount={}, chunkCount={}", 
                    userIds.size(), savedNotifications.size(), chunkFutures.size());
            
        } catch (Exception e) {
            log.error("[NOTIFICATION_BATCH_FAILED] productId={}, error={}", productId, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * 개별 저장 방식: 소량 사용자 처리 (기존 방식)
     * 메모리 효율을 위해 스트림 처리로 즉시 완료 대기
     */
    private void createAndSendNotificationsIndividual(
            List<Long> userIds, UUID productId, String productName, 
            Integer currentStock, Integer thresholdValue) {
        
        log.info("[NOTIFICATION_INDIVIDUAL_START] userIdCount={}, productId={}", userIds.size(), productId);
        
        // 메모리 효율: CompletableFuture를 리스트에 보관하지 않고 즉시 처리
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (Long userId : userIds) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // 개별 트랜잭션으로 저장
                    saveNotificationInTransaction(userId, productId, productName, currentStock, thresholdValue);
                } catch (Exception e) {
                    log.error("[NOTIFICATION_INDIVIDUAL_FAILED] userId={}, productId={}, error={}", 
                            userId, productId, e.getMessage(), e);
                }
            }, executorService);
            
            futures.add(future);
        }
        
        // 모든 작업 완료 대기 (메모리 효율: 즉시 처리)
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        log.info("[NOTIFICATION_INDIVIDUAL_COMPLETE] userIdCount={}", userIds.size());
    }
    
    /**
     * 개별 트랜잭션으로 알림 저장 및 SSE 전송
     * 중첩된 CompletableFuture를 제거하여 메모리 효율 향상
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void saveNotificationInTransaction(
            Long userId, UUID productId, String productName, 
            Integer currentStock, Integer thresholdValue) {
        
        // 알림 생성 및 저장
        Notification notification = Notification.create(userId, productId, productName, currentStock, thresholdValue);
        Notification saved = notificationRepository.save(notification);
        
        // SSE 전송은 트랜잭션 커밋 후 직접 호출
        // 호출부에서 이미 CompletableFuture로 감싸져 있으므로 중첩 제거
        try {
            sseService.sendNotification(userId, saved);
            log.debug("[NOTIFICATION_SSE_SENT] userId={}, notificationId={}", userId, saved.getId());
        } catch (Exception e) {
            log.error("[NOTIFICATION_SSE_FAILED] userId={}, error={}", userId, e.getMessage());
        }
    }
    
    /**
     * 애플리케이션 종료 시 스레드 풀 정리
     */
    @PreDestroy
    public void shutdown() {
        log.info("[NOTIFICATION_SERVICE_SHUTDOWN] Shutting down executor service...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("[NOTIFICATION_SERVICE_SHUTDOWN] Force shutting down executor service...");
                executorService.shutdownNow();
            }
            log.info("[NOTIFICATION_SERVICE_SHUTDOWN] Executor service shutdown complete");
        } catch (InterruptedException e) {
            log.error("[NOTIFICATION_SERVICE_SHUTDOWN] Interrupted during shutdown", e);
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}

