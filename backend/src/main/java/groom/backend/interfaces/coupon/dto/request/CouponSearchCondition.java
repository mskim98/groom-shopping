package groom.backend.interfaces.coupon.dto.request;

import groom.backend.domain.coupon.enums.CouponType;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponSearchCondition {

  // 쿠폰 이름 검색 (부분 일치)
  private String name;

  // 쿠폰 설명 키워드
  private String description;

  // 쿠폰 유형 (Enum)
  private CouponType type;

  // 활성 여부 (true=활성, false=비활성)
  private Boolean isActive;

  // 만료일 필터
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate expireDateFrom;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate expireDateTo;

  // 수량 범위
  private Long minQuantity;
  private Long maxQuantity;

  // 금액 범위
  private Integer minAmount;
  private Integer maxAmount;
}
