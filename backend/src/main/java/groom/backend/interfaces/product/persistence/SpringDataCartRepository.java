package groom.backend.interfaces.product.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataCartRepository extends JpaRepository<CartJpaEntity, Long> {
    @Query("SELECT c.productId FROM CartJpaEntity c WHERE c.user.id = :userId")
    List<UUID> findProductIdsByUserId(@Param("userId") Long userId);
    
    @Query("SELECT DISTINCT c.user.id FROM CartJpaEntity c WHERE c.productId = :productId")
    List<Long> findUserIdsByProductId(@Param("productId") UUID productId);
    
    @Query("SELECT c FROM CartJpaEntity c WHERE c.user.id = :userId AND c.productId = :productId")
    java.util.Optional<CartJpaEntity> findByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") UUID productId);
}

