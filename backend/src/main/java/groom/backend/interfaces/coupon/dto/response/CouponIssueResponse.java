package groom.backend.interfaces.coupon.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDate;

@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class CouponIssueResponse {
  private Long couponIssueId;
  private Long couponId;
  private LocalDate createdAt;
  private LocalDate deletedAt;
}
