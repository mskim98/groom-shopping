package groom.backend.domain.coupon.repository;

import groom.backend.domain.coupon.entity.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {
  List<CouponIssue> findCouponIssueByUserId(Long userId);
  List<CouponIssue> findByUserIdAndIsActiveTrueAndExpireDateAfter(Long userId, LocalDateTime currentDate);
}
