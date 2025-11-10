package groom.backend.interfaces.coupon.dto.request;

import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
@Schema(description = "쿠폰 생성 요청 DTO")
public class CouponCreateRequest {
  @NotBlank
  @Schema(description = "쿠폰 이름", example = "10% 할인 쿠폰")
  private String name;

  @Schema(description = "쿠폰 설명", example = "항상 감사하십시오")
  private String description;

  @NotNull
  @Schema(description = "쿠폰 수량", example = "1000")
  private Long quantity;

  @NotNull
  @Schema(description = "할인율 또는 할인 금액", example = "10")
  private Integer amount;

  @NotNull
  @Schema(description = "쿠폰 정책", example = "PERCENT")
  private CouponType type;

  @NotNull
  @Schema(description = "쿠폰 만료일", example = "2025-12-31")
  private LocalDate expireDate;

  public Coupon toEntity() {
    return Coupon.builder()
            .name(name)
            .description(description)
            .quantity(quantity)
            .amount(amount)
            .type(type)
            .expireDate(expireDate)
            .build();
  }
}
