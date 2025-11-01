package groom.backend.domain.coupon.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
public class CouponIssue {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Boolean isActive;

  private LocalDateTime createdAt;

  private LocalDateTime deletedAt;


  // coupon 1 : coupon issue N
//  @ManyToOne(fetch = FetchType.LAZY)
//  @JoinColumn(name = "user_id", nullable = false)
//  private User user;
  private Long userId;


  // coupon 1 : coupon issue N
//  @ManyToOne(fetch = FetchType.LAZY)
//  @JoinColumn(name = "coupon_id", nullable = false)
//  private Coupon coupon;
  private Long couponId;
}
