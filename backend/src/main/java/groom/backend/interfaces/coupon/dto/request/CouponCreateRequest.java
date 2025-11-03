package groom.backend.interfaces.coupon.dto.request;

import groom.backend.domain.coupon.entity.Coupon;
import groom.backend.domain.coupon.enums.CouponType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class CouponCreateRequest {
  @NotBlank
  private String name;

  private String description;

  @NotNull
  private Long quantity;

  @NotNull
  private Integer amount;

  @NotNull
  private CouponType type;

  @NotNull
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
