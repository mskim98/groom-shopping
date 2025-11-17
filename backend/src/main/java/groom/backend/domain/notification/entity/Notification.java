package groom.backend.domain.notification.entity;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
public class Notification {
    private final Long id;
    private final Integer currentStock;
    private final Integer thresholdValue;
    private final String message;
    private Boolean isRead;
    private final LocalDateTime createdAt;
    private final Long userId;
    private final UUID productId;

    public Notification(Long id, Integer currentStock, Integer thresholdValue, String message, Boolean isRead, LocalDateTime createdAt, Long userId, UUID productId) {
        this.id = id;
        this.currentStock = currentStock;
        this.thresholdValue = thresholdValue;
        this.message = message;
        this.isRead = isRead != null ? isRead : false;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.userId = userId;
        this.productId = productId;
        
        // 필수 필드 검증
        if (userId == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId cannot be null");
        }
    }

    public void markAsRead() {
        this.isRead = true;
    }

    public static Notification create(Long userId, UUID productId, Integer currentStock, Integer thresholdValue) {
        String message = String.format("재고가 %d개 남았어요", currentStock);
        return new Notification(null, currentStock, thresholdValue, message, false, LocalDateTime.now(), userId, productId);
    }

    /**
     * 제품명을 포함한 알림을 생성합니다.
     *
     * @param userId 사용자 ID
     * @param productId 제품 ID
     * @param productName 제품명
     * @param currentStock 현재 재고
     * @param thresholdValue 임계값
     * @return Notification 객체
     */
    public static Notification create(Long userId, UUID productId, String productName, Integer currentStock, Integer thresholdValue) {
        String message = String.format("%s의 재고가 %d개 남았어요", productName, currentStock);
        return new Notification(null, currentStock, thresholdValue, message, false, LocalDateTime.now(), userId, productId);
    }

    /**
     * 실시간 알림 전송용 Notification 객체를 생성합니다.
     * DB에 저장하고 SSE 전송도 수행합니다.
     * 
     * @param userId 사용자 ID
     * @param productId 제품 ID
     * @param message 알림 메시지
     * @return Notification 객체
     */
    public static Notification createForRealtime(Long userId, UUID productId, String message) {
        return new Notification(null, 15, 10, message, false, LocalDateTime.now(), userId, productId);
    }
}



