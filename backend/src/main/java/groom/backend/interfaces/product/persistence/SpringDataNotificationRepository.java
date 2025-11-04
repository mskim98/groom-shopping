package groom.backend.interfaces.product.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataNotificationRepository extends JpaRepository<NotificationJpaEntity, Long> {
    List<NotificationJpaEntity> findByUserId(Long userId);
    List<NotificationJpaEntity> findByUserIdAndIsReadFalse(Long userId);
}

