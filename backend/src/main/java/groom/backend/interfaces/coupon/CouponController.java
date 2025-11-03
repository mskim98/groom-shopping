package groom.backend.interfaces.coupon;

import groom.backend.application.coupon.CouponService;
import groom.backend.domain.auth.entity.User;
import groom.backend.infrastructure.security.CustomUserDetails;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/coupon")
public class CouponController {
  private final CouponService couponService;


  /**
   * 쿠폰 생성
   * POST /coupon
   */
  @PostMapping
  public ResponseEntity<CouponResponse> createCoupon(@RequestBody CouponCreateRequest request) {
    CouponResponse response = couponService.createCoupon(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /**
   * 쿠폰 단일 조회
   * GET /coupon/{coupon_id}
   */
  @GetMapping("/{coupon_id}")
  public ResponseEntity<CouponResponse> findCoupon(@PathVariable("id") Long couponId) {
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
          CouponSearchCondition condition,
          @PageableDefault(size = 10) Pageable pageable) {
    Page<CouponResponse> response = couponService.searchCoupon(condition, pageable);
    return ResponseEntity.ok(response);
  }

  /**
   * 쿠폰 수정
   * PUT /coupon/{id}
   */
  @PutMapping("/{id}")
  public ResponseEntity<CouponResponse> updateCoupon(
          @PathVariable("id") Long couponId,
          @RequestBody CouponUpdateRequest request) {
    CouponResponse response = couponService.updateCoupon(couponId, request);
    if (response == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(response);
  }

  /**
   * 쿠폰 삭제
   * DELETE /coupon/{id}
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteCoupon(@PathVariable("id") Long couponId) {
    Boolean result = couponService.deleteCoupon(couponId);
    if (result) {
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
  public ResponseEntity<CouponIssueResponse> issueCoupon(@PathVariable("coupon_id") Long couponId) {
    // TODO: credential 유효성 검사
    // TODO: 날짜 검증

    // 쿠폰 발급
    CouponIssueResponse response = couponService.issueCoupon(couponId);

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
    log.info("login identified : {}", user.getName());

    Long userId = user.getId();

    // 내 미사용 쿠폰 조회
    List<CouponIssueResponse> response = couponService.searchMyCoupon(userId);

    return ResponseEntity.ok(response);
  }

}
