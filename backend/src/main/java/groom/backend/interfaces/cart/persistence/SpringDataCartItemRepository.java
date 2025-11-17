package groom.backend.interfaces.cart.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataCartItemRepository extends JpaRepository<CartItemJpaEntity, Long> {
    
    @Query("SELECT ci.productId FROM CartItemJpaEntity ci WHERE ci.cart.user.id = :userId")
    List<UUID> findProductIdsByUserId(@Param("userId") Long userId);
    
    @Query("SELECT ci FROM CartItemJpaEntity ci WHERE ci.cart.id = :cartId AND ci.productId = :productId")
    Optional<CartItemJpaEntity> findByCartIdAndProductId(@Param("cartId") Long cartId, @Param("productId") UUID productId);

    @Query("SELECT ci FROM CartItemJpaEntity ci WHERE ci.cart.user.id = :userId")
    List<CartItemJpaEntity> findByUserId(@Param("userId") Long userId);
}




