package groom.backend.interfaces.coupon;

import groom.backend.application.coupon.CouponAsyncIssueService;
import groom.backend.application.coupon.CouponIssueService;
import groom.backend.common.annotation.CheckPermission;
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
import java.util.Map;

// @Tag : Swagger 문서에서 이 컨트롤러의 API들을 'Coupon' 그룹으로 묶어 보여준다.
@Tag(name = "Coupon", description = "쿠폰 발급 및 사용, 조회 API")
// @Validated : 파라미터(@PathVariable, @RequestParam 등)의 검증 어노테이션을 동작하게 한다.
@Validated
// @Slf4j : Lombok이 'log' 라는 Logger 객체를 자동 생성해줘 직접 선언할 필요가 없다.
@Slf4j
// @RestController : @Controller + @ResponseBody. 반환값을 자동으로 JSON 본문으로 변환한다.
@RestController
// @RequiredArgsConstructor : final 필드만 받는 생성자를 Lombok이 만들어, 스프링이 생성자 주입을 하게 한다.
@RequiredArgsConstructor
// @RequestMapping : 이 컨트롤러의 모든 URL 앞에 공통으로 붙는 기본 경로.
@RequestMapping("/v1/coupon")
// @CheckPermission : 커스텀 권한 검사 어노테이션(AOP). USER 또는 ADMIN 권한이 있어야 접근 가능.
@CheckPermission(roles = {"USER", "ADMIN"}, mode = CheckPermission.Mode.ANY, page = CheckPermission.Page.FO)
public class CouponController {
  // private final : 값이 한 번 주입되면 바뀌지 않도록 막고(final),
  // 의존성 주입 제어를 스프링에 넘기며, 외부에서 접근/교체할 수 없게 해(private) 안전하게 사용한다.
  private final CouponCommonService couponCommonService;
  private final CouponIssueService couponIssueService;
  private final CouponAsyncIssueService couponAsyncIssueService;

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
  // 쿠폰 발급 요청 처리 흐름:
  // 1) 요청 → 2) 사용자/요청시간 검증 → 3) 발급 서비스 호출 → 4) 응답(201 Created)
  // @PostMapping : HTTP POST 요청을 이 메서드에 매핑한다(자원 '생성' 의미).
  @PostMapping("/issue/{coupon_id}")
  public ResponseEntity<CouponIssueResponse> issueCoupon(
          // @AuthenticationPrincipal : JWT 인증 필터가 검증을 마친 뒤 SecurityContext에 담아둔
          // 사용자 정보를 파라미터로 자동 주입한다. 토큰 검증은 Security 계층이 이미 끝냈다.
          @Parameter(description = "JWT 인증 후 주입된 사용자 정보")
          @AuthenticationPrincipal(expression = "user") User user,
          // @RequestHeader : HTTP 요청 헤더의 값을 파라미터로 받는다(여기선 클라이언트 시각).
          @Parameter(description = "클라이언트 기준 UTC 시간", required = true, example = "Wed, 06 Nov 2025 15:00:00 GMT")
          @RequestHeader("Request-Date") Instant clientInstant,
          // @PathVariable : URL 경로의 {coupon_id} 부분을 파라미터로 추출한다.
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
          summary = "쿠폰 비동기 발급 (대규모 트래픽용)",
          description = """
          발급 요청을 큐에 적재하고 즉시 requestId 를 반환합니다(202 Accepted).
          동일 쿠폰 요청은 단일 컨슈머가 직렬 처리하므로 분산 락 없이 동시성이 해소됩니다.
          처리 결과는 상태 조회 API 로 polling 합니다.
          """
  )
  @ApiResponses({
          @ApiResponse(responseCode = "202", description = "요청 접수(처리 대기)"),
          @ApiResponse(responseCode = "404", description = "존재하지 않는 쿠폰")
  })
  @PostMapping("/issue-async/{coupon_id}")
  public ResponseEntity<Map<String, String>> issueCouponAsync(
          @Parameter(description = "JWT 인증 후 주입된 사용자 정보")
          @AuthenticationPrincipal(expression = "user") User user,
          @Parameter(description = "쿠폰 ID", example = "1")
          @PathVariable("coupon_id") Long couponId) {
    String requestId = couponAsyncIssueService.enqueue(couponId, user.getId());
    // 202 Accepted: "요청은 받았고 처리는 비동기로 진행 중". 대기 순번은 응답하지 않는다 -
    // 발급 판정은 컨슈머 도달 순서로 정해지므로, 큐에 적재된 순번을 돌려주면 지킬 수 없는 순서를 약속하게 된다.
    return ResponseEntity.accepted().body(Map.of(
            "requestId", requestId,
            "status", "WAITING"));
  }

  @Operation(
          summary = "쿠폰 비동기 발급 상태 조회",
          description = "requestId 로 발급 처리 상태(WAITING/SUCCESS/FAILED:사유/UNKNOWN)를 조회합니다."
  )
  @GetMapping("/issue-async/{request_id}/status")
  public ResponseEntity<Map<String, String>> getIssueStatus(
          @Parameter(description = "발급 요청 ID")
          @PathVariable("request_id") String requestId) {
    String status = couponAsyncIssueService.getStatus(requestId);
    return ResponseEntity.ok(Map.of("requestId", requestId, "status", status));
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
