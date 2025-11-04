package groom.backend.domain.notification.repository;

import groom.backend.domain.notification.entity.Notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {
    Notification save(Notification notification);
    Optional<Notification> findById(Long id);
    List<Notification> findByUserId(Long userId);
    List<Notification> findUnreadByUserId(Long userId);
    List<Long> findUserIdsWithProductInCart(UUID productId);
    void deleteById(Long id);
    void deleteAllById(List<Long> ids);
    void deleteByUserId(Long userId);
    List<Notification> findByIds(List<Long> ids);
}

