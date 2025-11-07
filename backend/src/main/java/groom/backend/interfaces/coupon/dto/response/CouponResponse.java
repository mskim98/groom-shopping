package groom.backend.interfaces.coupon.dto.response;

import groom.backend.domain.coupon.entity.Coupon;
import groom.backend.domain.coupon.enums.CouponType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
@Getter
public class CouponResponse {
  @Schema(description = "쿠폰 식별자", example = "125523")
  private Long id;

  @Schema(description = "쿠폰 이름", example = "10% 할인 쿠폰")
  private String name;

  @Schema(description = "쿠폰 설명", example = "항상 감사하십시오")
  private String description;

  @Schema(description = "쿠폰 수량", example = "1000")
  private Long quantity;

  @Schema(description = "할인율 또는 할인 금액", example = "10")
  private Integer amount;

  @Schema(description = "쿠폰 발급 가능 여부", example = "true")
  private Boolean isActive;

  @Schema(description = "쿠폰 정책", example = "PERCENT")
  private CouponType type;

  @Schema(description = "쿠폰 만료일", example = "2025-12-31")
  private LocalDate expireDate;

  public static CouponResponse from(Coupon coupon) {
    return CouponResponse.builder()
            .id(coupon.getId())
            .name(coupon.getName())
            .description(coupon.getDescription())
            .quantity(coupon.getQuantity())
            .amount(coupon.getAmount())
            .isActive(coupon.getIsActive())
            .type(coupon.getType())
            .expireDate(coupon.getExpireDate())
            .build();
  }
}
