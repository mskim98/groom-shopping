package groom.backend.interfaces.product.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataNotificationRepository extends JpaRepository<NotificationJpaEntity, Long> {
    List<NotificationJpaEntity> findByUserId(Long userId);
    List<NotificationJpaEntity> findByUserIdAndIsReadFalse(Long userId);
    
    @Modifying
    @Query("DELETE FROM NotificationJpaEntity n WHERE n.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}

