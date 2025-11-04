package groom.backend.domain.coupon.entity;

import jakarta.persistence.*;
import lombok.*;

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

  @Builder.Default
  @Setter
  private Boolean isActive=true;

  private LocalDateTime createdAt;

  // 만료일 및 사용 시간을 나타내는 필드
  // isActive = true 일 시, 만료일
  // isActive = false 일 시, 사용일
  @Setter
  private LocalDateTime deletedAt;


//  coupon 1 : coupon issue N
//  @ManyToOne(fetch = FetchType.LAZY)
//  @JoinColumn(name = "user_id", nullable = false)
//  private User user;
  private Long userId;


  // coupon 1 : coupon issue N
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "coupon_id", nullable = false)
  private Coupon coupon;
}
