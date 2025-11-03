package groom.backend.domain.coupon.repository;

import groom.backend.interfaces.coupon.dto.request.CouponSearchCondition;
import groom.backend.domain.coupon.entity.Coupon;
import org.springframework.data.repository.query.Param;
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
    (:#{#condition.name} IS NULL OR c.name LIKE %:#{#condition.name}%)
    AND (:#{#condition.description} IS NULL OR c.description LIKE %:#{#condition.description}%)
    AND (:#{#condition.type} IS NULL OR c.type = :#{#condition.type})
    AND (:#{#condition.isActive} IS NULL OR c.isActive = :#{#condition.isActive})
    AND (:#{#condition.expireDateFrom} IS NULL OR c.expireDate >= :#{#condition.expireDateFrom})
    AND (:#{#condition.expireDateTo} IS NULL OR c.expireDate <= :#{#condition.expireDateTo})
    AND (:#{#condition.minQuantity} IS NULL OR c.quantity >= :#{#condition.minQuantity})
    AND (:#{#condition.maxQuantity} IS NULL OR c.quantity <= :#{#condition.maxQuantity})
    AND (:#{#condition.minAmount} IS NULL OR c.amount >= :#{#condition.minAmount})
    AND (:#{#condition.maxAmount} IS NULL OR c.amount <= :#{#condition.maxAmount})
  """)
  Page<Coupon> searchByCondition(@Param("condition") CouponSearchCondition condition, Pageable pageable);
}
