package groom.backend.interfaces.cart.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataCartRepository extends JpaRepository<CartJpaEntity, Long> {
    
    @Query("SELECT c FROM CartJpaEntity c WHERE c.user.id = :userId")
    Optional<CartJpaEntity> findByUserId(@Param("userId") Long userId);

    // 결제 승인이 같은 사용자의 장바구니 수량을 동시에 빼지 않게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CartJpaEntity c WHERE c.user.id = :userId")
    Optional<CartJpaEntity> findByUserIdForUpdate(@Param("userId") Long userId);

    @Query("SELECT DISTINCT c.user.id FROM CartJpaEntity c JOIN c.cartItems ci WHERE ci.productId = :productId")
    java.util.List<Long> findUserIdsByProductId(@Param("productId") java.util.UUID productId);
}




