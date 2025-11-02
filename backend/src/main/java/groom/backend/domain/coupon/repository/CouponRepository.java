package groom.backend.domain.coupon.repository;

import groom.backend.interfaces.coupon.dto.request.CouponSearchCondition;
import groom.backend.domain.coupon.entity.Coupon;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {
  @Query("""
  SELECT c
  FROM Coupon c
  WHERE
    (:#{#cond.name} IS NULL OR c.name LIKE %:#{#cond.name}%)
    AND (:#{#cond.description} IS NULL OR c.description LIKE %:#{#cond.description}%)
    AND (:#{#cond.type} IS NULL OR c.type = :#{#cond.type})
    AND (:#{#cond.isActive} IS NULL OR c.isActive = :#{#cond.isActive})
    AND (:#{#cond.expireDateFrom} IS NULL OR c.expireDate >= :#{#cond.expireDateFrom})
    AND (:#{#cond.expireDateTo} IS NULL OR c.expireDate <= :#{#cond.expireDateTo})
    AND (:#{#cond.minQuantity} IS NULL OR c.quantity >= :#{#cond.minQuantity})
    AND (:#{#cond.maxQuantity} IS NULL OR c.quantity <= :#{#cond.maxQuantity})
    AND (:#{#cond.minAmount} IS NULL OR c.amount >= :#{#cond.minAmount})
    AND (:#{#cond.maxAmount} IS NULL OR c.amount <= :#{#cond.maxAmount})
  """)
  Page<Coupon> searchByCondition(@Param("condition") CouponSearchCondition condition, Pageable pageable);
}
