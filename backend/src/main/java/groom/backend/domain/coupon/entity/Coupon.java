package groom.backend.domain.coupon.entity;

import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
import groom.backend.domain.coupon.enums.CouponType;
import jakarta.persistence.*;
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
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;

  private String description;

  private Long quantity;

  private Integer amount;

  @Builder.Default
  private Boolean isActive = true;

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
    if (request.getIsActive() != null) {
      this.isActive = request.getIsActive();
    }
  }

  public void decreaseQuantity() {
    if (this.quantity != null && this.quantity > 0) {
      this.quantity--;
    } else {
      throw new IllegalStateException("Cannot decrease quantity below zero.");
    }
  }
}
