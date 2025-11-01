package groom.backend.domain.coupon.entity;

import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
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

  public void update(CouponUpdateRequest request) {
    if (request.getName() != null) {
      this.name = request.getName();
    }
    if (request.getDescription() != null) {
      this.description = request.getDescription();
    }
    if (request.getQuantity() != null) {
      this.quantity = request.getQuantity();
    }
    if (request.getExpireDate() != null) {
      this.expireDate = request.getExpireDate();
    }
  }

  public void decreaseQuantity() {
    this.quantity--;
  }
}
