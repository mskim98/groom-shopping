package groom.backend.interfaces.coupon.dto.response;

import groom.backend.domain.coupon.model.entity.CouponIssue;
import groom.backend.domain.coupon.model.enums.CouponType;
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
  @Schema(description = "사용자 id", example = "12345")
  private Long userId;
  @Schema(description = "쿠폰 생성일", example = "2025-10-31")
  private LocalDateTime createdAt;
  @Schema(description = "쿠폰 만료일, 쿠폰 사용 시 사용일을 나타냄.", example = "2025-12-31")
  private LocalDateTime deletedAt;
  @Schema(description = "할인 ")
  private CouponType couponType;
  @Schema(description = "할인 시 사용하는 수치", example = "3000")
  private Integer discountValue;
  @Schema(description = "할인 정책 : 최대 할인 금액")
  private Integer maximumDiscount;
  @Schema(description = "할인 정책 : 최소 구매 금액")
  private Integer minimumCost;
  @Schema(description = "쿠폰 활성화 여부")
  private Boolean isActive;

  public static CouponIssueResponse from(CouponIssue couponIssue) {
    return CouponIssueResponse.builder()
            .couponId(couponIssue.getCoupon().getId())
            .couponIssueId(couponIssue.getId())
            .userId(couponIssue.getUserId())
            .createdAt(couponIssue.getCreatedAt())
            .deletedAt(couponIssue.getDeletedAt())
            .couponType(couponIssue.getCoupon().getType())
            .discountValue(couponIssue.getCoupon().getAmount())
            .maximumDiscount(couponIssue.getCoupon().getMaximumDiscount())
            .minimumCost(couponIssue.getCoupon().getMinimumCost())
            .isActive(couponIssue.getCoupon().getIsActive())
            .build();
  }
}
