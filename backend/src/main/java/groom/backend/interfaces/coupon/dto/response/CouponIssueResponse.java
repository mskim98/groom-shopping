package groom.backend.interfaces.coupon.dto.response;

import groom.backend.domain.coupon.entity.CouponIssue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class CouponIssueResponse {
  private Long couponIssueId;
  private Long couponId;
  private LocalDateTime createdAt;
  private LocalDateTime deletedAt;

  public static CouponIssueResponse from(CouponIssue couponIssue) {
    return CouponIssueResponse.builder()
            .couponId(couponIssue.getCouponId())
            .couponIssueId(couponIssue.getId())
            .createdAt(couponIssue.getCreatedAt())
            .deletedAt(couponIssue.getDeletedAt())
            .build();
  }
}
