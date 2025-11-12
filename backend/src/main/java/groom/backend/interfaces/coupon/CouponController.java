package groom.backend.interfaces.coupon;

import groom.backend.application.coupon.CouponIssueService;
import groom.backend.domain.coupon.service.CouponCommonService;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.auth.entity.User;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import groom.backend.interfaces.coupon.dto.request.CouponSearchCondition;
import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Tag(name = "Coupon", description = "쿠폰 발급 및 사용 관련 API")
@Validated
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/coupon")
public class CouponController {
  private final CouponCommonService couponCommonService;
  private final CouponIssueService couponIssueService;

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

  @GetMapping("/{coupon_id}")
  @Operation(summary = "단일 쿠폰 조회", description = "지정된 id의 쿠폰을 조회합니다.")
  @ApiResponses(value = {
          @ApiResponse(responseCode = "200", description = "Created",
                  content = {@Content(schema = @Schema(implementation = CouponResponse.class))}),
          @ApiResponse(responseCode = "404", description = "Not Found")
  })
  public ResponseEntity<CouponResponse> findCoupon(
          @PathVariable("coupon_id")
          @Schema(description = "Path Value", example = "1")
          Long couponId) {
    CouponResponse response = couponCommonService.findCoupon(couponId);
    if (response == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping
  @Operation(
          summary = "쿠폰 조건부 검색 (페이징)",
          description = "쿠폰 이름, 타입, 활성 상태 등의 조건을 이용해 쿠폰을 검색합니다."
  )
  @ApiResponses({
          @ApiResponse(responseCode = "200", description = "검색 성공",
                  content = @Content(schema = @Schema(implementation = CouponResponse.class))
          ),
//          @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터",
//                  content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  })
  public ResponseEntity<Page<CouponResponse>> searchCoupon(
          @Parameter(description = "검색 조건", required = false)
          @ModelAttribute CouponSearchCondition condition,
          @Parameter(description = "페이징 정보", required = false)
          @PageableDefault(size = 10) Pageable pageable) {
    Page<CouponResponse> response = couponCommonService.searchCoupon(condition, pageable);
    return ResponseEntity.ok(response);
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

  @Operation(
          summary = "쿠폰 발급",
          description = """
          지정된 쿠폰 ID의 쿠폰을 현재 로그인한 사용자에게 발급합니다.
          요청 헤더의 Date 값과 서버 시간의 차이가 1분 이상이면 거부됩니다.
          """
  )
  @ApiResponses({
          @ApiResponse(responseCode = "201", description = "쿠폰 발급 성공",
                  content = @Content(schema = @Schema(implementation = CouponIssueResponse.class))),
          @ApiResponse(responseCode = "403", description = "시간 오차 초과 (요청 거부)",
                  content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
          @ApiResponse(responseCode = "404", description = "존재하지 않는 쿠폰",
                  content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  })
  @PostMapping("/issue/{coupon_id}")
  public ResponseEntity<CouponIssueResponse> issueCoupon(
          @Parameter(description = "JWT 인증 후 주입된 사용자 정보")
          @AuthenticationPrincipal(expression = "user") User user,
          @Parameter(description = "클라이언트 기준 UTC 시간", required = true, example = "Wed, 06 Nov 2025 15:00:00 GMT")
          @RequestHeader("Request-Date") Instant clientInstant,
          @Parameter(description = "쿠폰 ID", example = "1")
          @PathVariable("coupon_id") Long couponId) {

    // 사용자 정보 추출
    // 토큰 유효성 검사는 security 측에서 한다.
    log.info("user identified : {}", user.getName());

    // 서버와 클라이언트 간의 시간 오차 검증 (절대값 기준)
    Duration diff = Duration.between(clientInstant, Instant.now()).abs();

    // TODO : 요청 트래픽으로 인해 느려질 경우를 고려해야 할 수 있다.
    // 2차에서 다뤄야 할 사항으로 보임.
    // 예상 사용자 책정과 성능 요구사항 설정으로 최대 몇 초 이내에 응답해야 하는지에 따라, 오차 또한 달라질 수 있음.
    // 분 단위 이내만 허용
    if (diff.toMinutes() >= 1) {
      log.warn("Time difference exceeded: {} seconds", diff.toSeconds());
      throw new BusinessException(ErrorCode.INVALID_PARAMETER, "잘못된 요청입니다.");
    }

    // 쿠폰 발급
    CouponIssueResponse response = couponIssueService.issueCoupon(couponId, user);

    // 쿠폰이 존재하지 않을 시
    if (response == null) {
      return ResponseEntity.notFound().build();
    }

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @Operation(
          summary = "내 쿠폰 조회",
          description = "로그인한 사용자의 미사용 쿠폰 목록을 조회합니다."
  )
  @ApiResponses({
          @ApiResponse(responseCode = "200", description = "조회 성공",
                  content = @Content(schema = @Schema(implementation = CouponIssueResponse.class))),
          @ApiResponse(responseCode = "401", description = "인증 실패",
                  content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  })
  @GetMapping("/me")
  public ResponseEntity<List<CouponIssueResponse>> myCoupon(
          @Parameter(description = "JWT 인증 후 주입된 사용자 정보")
          @AuthenticationPrincipal(expression = "user") User user) {
    // 사용자 정보 추출
    // 토큰 유효성 검사는 security 측에서 한다.
    log.info("user identified : {}", user.getName());

    Long userId = user.getId();

    // 내 미사용 쿠폰 조회
    List<CouponIssueResponse> response = couponIssueService.searchMyCoupon(userId);

    return ResponseEntity.ok(response);
  }

}
