package groom.backend.domain.coupon.model.entity;

import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
import groom.backend.domain.coupon.model.enums.CouponType;
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

  // S2 이후 뜻이 바뀌었다. 남은 수량이 아니라 <b>불변 발행 수량</b>이고 확정 경로가 감산하지 않는다
  // 감산을 없앤 것이 S2 의 본체다 - 그 문장이 있는 한 같은 쿠폰의 모든 확정이 한 행을 두고 줄을 선다
  private Long quantity;

  // 마감 시 1회 굳히는 확정 발급 수. null 이면 진행 중이라 COUNT 로 세야 한다
  // 카운터를 따로 두고 매번 갱신하지 않는 이유는 그 값과 행 수가 어긋날 여지를 없애기 위해서다
  private Long issuedCount;

  private Integer amount;

  private Integer maximumDiscount;

  private Integer minimumCost;

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
