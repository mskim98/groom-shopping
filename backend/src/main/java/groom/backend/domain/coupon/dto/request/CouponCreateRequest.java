package groom.backend.domain.coupon.dto.request;

import groom.backend.domain.coupon.entity.Coupon;
import groom.backend.domain.coupon.enums.CouponType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class CouponCreateRequest {

  private String name;

  private String description;

  private Long quantity;

  private Integer amount;

  private CouponType type;

  private LocalDate expireDate;

  public Coupon toEntity() {
    return Coupon.builder()
            .name(name)
            .description(description)
            .quantity(quantity)
            .amount(amount)
            .type(type)
            .expireDate(expireDate)
            .build();
  }
}
