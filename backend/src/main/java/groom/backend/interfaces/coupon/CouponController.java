package groom.backend.interfaces.coupon;

import groom.backend.application.coupon.CouponIssueService;
import groom.backend.application.coupon.CouponService;
import groom.backend.domain.auth.entity.User;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import groom.backend.interfaces.coupon.dto.request.CouponSearchCondition;
import groom.backend.interfaces.coupon.dto.request.CouponUpdateRequest;
import groom.backend.interfaces.coupon.dto.response.CouponIssueResponse;
import groom.backend.interfaces.coupon.dto.response.CouponResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Validated
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/coupon")
public class CouponController {
  private final CouponService couponService;
  private final CouponIssueService couponIssueService;

  /**
   * 쿠폰 생성
   * POST /coupon
   */
  @PostMapping
  public ResponseEntity<CouponResponse> createCoupon(@Validated @RequestBody CouponCreateRequest request) {
    CouponResponse response = couponService.createCoupon(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * 쿠폰 단일 조회
   * GET /coupon/{coupon_id}
   */
  @GetMapping("/{coupon_id}")
  public ResponseEntity<CouponResponse> findCoupon(@PathVariable("coupon_id") Long couponId) {
    CouponResponse response = couponService.findCoupon(couponId);
    if (response == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(response);
  }

  /**
   * 쿠폰 조건부 검색 (페이징)
   * GET /coupon?name=테스트&type=DISCOUNT&isActive=true&page=0&size=10
   */
  @GetMapping
  public ResponseEntity<Page<CouponResponse>> searchCoupon(
          @ModelAttribute CouponSearchCondition condition,
          @PageableDefault(size = 10) Pageable pageable) {
    Page<CouponResponse> response = couponService.searchCoupon(condition, pageable);
    return ResponseEntity.ok(response);
  }

  /**
   * 쿠폰 수정
   * PUT /coupon/{coupon_id}
   */
  @PutMapping("/{coupon_id}")
  public ResponseEntity<CouponResponse> updateCoupon(
          @PathVariable("coupon_id") Long couponId,
          @RequestBody CouponUpdateRequest request) {
    CouponResponse response = couponService.updateCoupon(couponId, request);
    if (response == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(response);
  }

  /**
   * 쿠폰 삭제
   * DELETE /coupon/{coupon_id}
   */
  @DeleteMapping("/{coupon_id}")
  public ResponseEntity<Void> deleteCoupon(@PathVariable("coupon_id") Long couponId) {
    Boolean result = couponService.deleteCoupon(couponId);
    if (!result) {
      // 삭제 실패 (존재하지 않음)
      return ResponseEntity.notFound().build();
    }
    // 삭제 성공
    return ResponseEntity.noContent().build();
  }

  /**
   * 쿠폰 발급
   * POST /coupon/issue/{coupon_id}
   */
  @PostMapping("/issue/{coupon_id}")
  public ResponseEntity<CouponIssueResponse> issueCoupon(@AuthenticationPrincipal(expression = "user") User user,
                                                         @RequestHeader("Date") Instant clientInstant,
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
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
              .build();
    }

    // 쿠폰 발급
    CouponIssueResponse response = couponIssueService.issueCoupon(couponId, user);

    // 쿠폰이 존재하지 않을 시
    if (response == null) {
      return ResponseEntity.notFound().build();
    }

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * 내 쿠폰 조회
   * GET /coupon/me
   */
  @GetMapping("/me")
  public ResponseEntity<List<CouponIssueResponse>> myCoupon(@AuthenticationPrincipal(expression = "user") User user) {
    // 사용자 정보 추출
    // 토큰 유효성 검사는 security 측에서 한다.
    log.info("user identified : {}", user.getName());

    Long userId = user.getId();

    // 내 미사용 쿠폰 조회
    List<CouponIssueResponse> response = couponIssueService.searchMyCoupon(userId);

    return ResponseEntity.ok(response);
  }

}
