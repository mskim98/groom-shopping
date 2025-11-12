package groom.backend.domain.coupon.repository;

import groom.backend.domain.coupon.model.entity.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {
  List<CouponIssue> findCouponIssueByUserId(Long userId);
  List<CouponIssue> findByUserIdAndIsActiveTrueAndDeletedAtAfter(Long userId, LocalDateTime currentDate);
  Optional<CouponIssue> findByCouponIdAndUserId(Long couponId, Long userId);
  List<CouponIssue> findByCouponIdInAndUserId(List<Long> couponId, Long userId);
}
