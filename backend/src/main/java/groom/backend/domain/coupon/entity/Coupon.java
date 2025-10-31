package groom.backend.domain.coupon.entity;

import groom.backend.domain.coupon.enums.CouponType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
public class Coupon {
  @Id
  private Long id;

  private String name;

  private String description;

  private Long quantity;

  private Integer amount;

  private Boolean isActive;

  @Enumerated(EnumType.STRING)
  private CouponType type;

  private LocalDate expireDate;

}
