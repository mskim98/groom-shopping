package groom.backend.interfaces.product.persistence;

import groom.backend.domain.notification.entity.Notification;
import groom.backend.domain.notification.repository.NotificationRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JpaNotificationRepository implements NotificationRepository {

    private final SpringDataNotificationRepository springRepo;
    private final SpringDataCartRepository cartRepo;

    public JpaNotificationRepository(SpringDataNotificationRepository springRepo, SpringDataCartRepository cartRepo) {
        this.springRepo = springRepo;
        this.cartRepo = cartRepo;
    }

    @Override
    public Notification save(Notification notification) {
        NotificationJpaEntity saved = springRepo.save(toEntity(notification));
        return toDomain(saved);
    }

    @Override
    public Optional<Notification> findById(Long id) {
        return springRepo.findById(id).map(this::toDomain);
    }

    @Override
    public List<Notification> findByUserId(Long userId) {
        return springRepo.findByUserId(userId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Notification> findUnreadByUserId(Long userId) {
        return springRepo.findByUserIdAndIsReadFalse(userId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> findUserIdsWithProductInCart(UUID productId) {
        return cartRepo.findUserIdsByProductId(productId);
    }

    private Notification toDomain(NotificationJpaEntity e) {
        return new Notification(
                e.getId(),
                e.getCurrentStock(),
                e.getThresholdValue(),
                e.getMessage(),
                e.getIsRead(),
                e.getCreatedAt(),
                e.getUserId(),
                e.getProductId()
        );
    }

    private NotificationJpaEntity toEntity(Notification n) {
        return NotificationJpaEntity.builder()
                .id(n.getId())
                .currentStock(n.getCurrentStock())
                .thresholdValue(n.getThresholdValue())
                .message(n.getMessage())
                .isRead(n.getIsRead())
                .createdAt(n.getCreatedAt())
                .userId(n.getUserId())
                .productId(n.getProductId())
                .build();
    }
}

