package groom.backend.interfaces.coupon.dto.response;

import groom.backend.domain.coupon.entity.Coupon;
import groom.backend.domain.coupon.enums.CouponType;
import lombok.*;

import java.time.LocalDate;

@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
@Getter
public class CouponResponse {
  private Long id;

  private String name;

  private String description;

  private Long quantity;

  private Integer amount;

  private Boolean isActive;

  private CouponType type;

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
