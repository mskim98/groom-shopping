package groom.backend.interfaces.coupon.dto.request;

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

  private Boolean isActive;
}
