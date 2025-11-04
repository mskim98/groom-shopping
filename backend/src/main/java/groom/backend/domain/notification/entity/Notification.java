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
        this.createdAt = createdAt;
        this.userId = userId;
        this.productId = productId;
    }

    public void markAsRead() {
        this.isRead = true;
    }

    public static Notification create(Long userId, UUID productId, Integer currentStock, Integer thresholdValue) {
        String message = String.format("재고가 %d개로 얼마 남지 않았어요", currentStock);
        return new Notification(null, currentStock, thresholdValue, message, false, LocalDateTime.now(), userId, productId);
    }
}

