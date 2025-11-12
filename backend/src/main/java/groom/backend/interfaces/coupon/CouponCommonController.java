package groom.backend.interfaces.coupon;

import groom.backend.application.coupon.CouponIssueService;
import groom.backend.domain.coupon.service.CouponCommonService;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import groom.backend.interfaces.coupon.dto.request.CouponSearchCondition;
import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
import groom.backend.interfaces.coupon.dto.response.CouponResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Coupon Management", description = "쿠폰 관리 API(생성, 수정, 삭제)")
@Validated
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/coupon")
public class CouponCommonController {
  private final CouponCommonService couponCommonService;

  @PostMapping
  @Operation(summary = "쿠폰 생성", description = "지정된 값으로 쿠폰을 생성합니다. 할인 정책을 적용시 해당 정책의 수치는 변경할 수 없습니다.")
  @ApiResponses(value = {
          @ApiResponse(responseCode = "201", description = "Created",
                  content = {@Content(schema = @Schema(implementation = CouponResponse.class))}),
  })
  public ResponseEntity<CouponResponse> createCoupon(@Validated @RequestBody CouponCreateRequest request) {
    CouponResponse response = couponCommonService.createCoupon(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @Operation(
          summary = "쿠폰 수정",
          description = "쿠폰 ID를 기반으로 쿠폰 정보를 수정합니다."
  )
  @ApiResponses({
          @ApiResponse(responseCode = "200", description = "수정 성공",
                  content = @Content(schema = @Schema(implementation = CouponResponse.class))),
          @ApiResponse(responseCode = "404", description = "수정 실패",
                  content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
  })
  @PutMapping("/{coupon_id}")
  public ResponseEntity<CouponResponse> updateCoupon(
          @Parameter(description = "쿠폰 ID", example = "1")
          @PathVariable("coupon_id") Long couponId,
          @RequestBody CouponUpdateRequest request) {
    CouponResponse response = couponCommonService.updateCoupon(couponId, request);
    if (response == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(response);
  }

  @Operation(
          summary = "쿠폰 삭제",
          description = "쿠폰 ID를 기반으로 쿠폰을 삭제합니다."
  )
  @ApiResponses({
          @ApiResponse(responseCode = "204", description = "삭제 성공"),
          @ApiResponse(responseCode = "404", description = "삭제 실패"),
//          TODO : 현재 Coupon 삭제 시 실패 요청은 메시지를 던지지 않으며, 컨트롤러에서 직접 검증함.
//          @ApiResponse(responseCode = "404", description = "요청한 리소스를 찾을 수 없습니다.",
//                  content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  })
  @DeleteMapping("/{coupon_id}")
  public ResponseEntity<Void> deleteCoupon(@PathVariable("coupon_id") Long couponId) {
    Boolean result = couponCommonService.deleteCoupon(couponId);
    if (!result) {
      // 삭제 실패 (존재하지 않음)
      return ResponseEntity.notFound().build();
    }
    // 삭제 성공
    return ResponseEntity.noContent().build();
  }
}
