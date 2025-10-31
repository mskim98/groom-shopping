package groom.backend.domain.coupon.dto.request;

import groom.backend.domain.coupon.enums.CouponType;
import lombok.*;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class CouponUpdateRequest {

  private String name;

  private String description;

  private Long quantity;

  private LocalDate expireDate;
}
