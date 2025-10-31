package groom.backend.domain.coupon.repository;

import groom.backend.domain.coupon.dto.request.CouponSearchCondition;
import groom.backend.domain.coupon.entity.Coupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {
  Page<Coupon> searchByCondition(CouponSearchCondition condition, Pageable pageable);
}
