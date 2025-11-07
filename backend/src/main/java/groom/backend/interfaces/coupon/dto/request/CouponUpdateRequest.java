package groom.backend.interfaces.coupon.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
@Schema(description = "쿠폰 수정 요청 DTO")
public class CouponUpdateRequest {

  @Schema(description = "쿠폰 이름", example = "10% 할인 쿠폰")
  private String name;

  @Schema(description = "쿠폰 설명", example = "항상 감사하십시오")
  private String description;

  @Schema(description = "쿠폰 수량", example = "1000")
  private Long quantity;

  @Schema(description = "쿠폰 만료일", example = "2025-12-31")
  private LocalDate expireDate;

  @Schema(description = "쿠폰 활성화 여부", example = "true")
  private Boolean isActive;
}
