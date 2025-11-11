package groom.backend.interfaces.cart.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataCartRepository extends JpaRepository<CartJpaEntity, Long> {
    
    @Query("SELECT c FROM CartJpaEntity c WHERE c.user.id = :userId")
    Optional<CartJpaEntity> findByUserId(@Param("userId") Long userId);

    @Query("SELECT DISTINCT c.user.id FROM CartJpaEntity c JOIN c.cartItems ci WHERE ci.productId = :productId")
    java.util.List<Long> findUserIdsByProductId(@Param("productId") java.util.UUID productId);
}



