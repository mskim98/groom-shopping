package groom.backend.interfaces.product.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataNotificationRepository extends JpaRepository<NotificationJpaEntity, Long> {
    List<NotificationJpaEntity> findByUserId(Long userId);
    List<NotificationJpaEntity> findByUserIdAndIsReadFalse(Long userId);
    
    @Query("SELECT DISTINCT c.user.id FROM CartJpaEntity c WHERE c.productId = :productId")
    List<Long> findUserIdsWithProductInCart(@Param("productId") UUID productId);
}

