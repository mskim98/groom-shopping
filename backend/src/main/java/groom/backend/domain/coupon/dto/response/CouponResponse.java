package groom.backend.domain.coupon.dto.response;

import groom.backend.domain.coupon.entity.Coupon;
import groom.backend.domain.coupon.enums.CouponType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDate;

@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
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
