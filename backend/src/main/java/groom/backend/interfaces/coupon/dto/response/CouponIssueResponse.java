package groom.backend.interfaces.coupon.dto.response;

import groom.backend.domain.coupon.entity.CouponIssue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
@Getter
public class CouponIssueResponse {
  @Schema(description = "발급된 쿠폰 id", example = "1245")
  private Long couponIssueId;
  @Schema(description = "쿠폰 id", example = "21")
  private Long couponId;
  @Schema(description = "쿠폰 생성일", example = "2025-10-31")
  private LocalDateTime createdAt;
  @Schema(description = "쿠폰 만료일, 쿠폰 사용 시 사용일을 나타냄.", example = "2025-12-31")
  private LocalDateTime deletedAt;

  public static CouponIssueResponse from(CouponIssue couponIssue) {
    return CouponIssueResponse.builder()
            .couponId(couponIssue.getCoupon().getId())
            .couponIssueId(couponIssue.getId())
            .createdAt(couponIssue.getCreatedAt())
            .deletedAt(couponIssue.getDeletedAt())
            .build();
  }
}
