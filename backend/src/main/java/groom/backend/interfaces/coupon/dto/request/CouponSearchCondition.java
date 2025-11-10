package groom.backend.interfaces.coupon.dto.request;

import groom.backend.domain.coupon.model.enums.CouponType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "쿠폰 검색 요청 DTO")
public class CouponSearchCondition {

  @Schema(description = "쿠폰 이름 검색 (부분 일치)", example = "10% 할인")
  private String name;

  @Schema(description = "쿠폰 설명 키워드", example = "감사")
  private String description;

  @Schema(description = "쿠폰 정책", example = "PERCENT")
  private CouponType type;

  @Schema(description = "쿠폰 활성화 여부", example = "true")
  private Boolean isActive;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  @Schema(description = "쿠폰 만료일 시작 시간", example = "2025-10-31")
  private LocalDate expireDateFrom;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  @Schema(description = "쿠폰 만료일 종료 시간", example = "2025-12-31")
  private LocalDate expireDateTo;

  @Schema(description = "쿠폰 최소 수량", example = "2025-10-31")
  private Long minQuantity;
  @Schema(description = "쿠폰 최대 수량", example = "2025-10-31")
  private Long maxQuantity;

  @Schema(description = "최소 할인율 또는 할인 금액", example = "10")
  private Integer minAmount;
  @Schema(description = "최대 할인율 또는 할인 금액", example = "90")
  private Integer maxAmount;
}
