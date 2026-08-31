package groom.backend.domain.coupon.repository;

import groom.backend.domain.coupon.model.entity.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// @Repository : 영속성 계층 빈으로 등록하고, JPA 예외를 스프링 공통 예외로 변환해준다.
// JpaRepository 를 상속하면 save/findById/findAll 등 기본 CRUD 구현을 스프링이 자동 생성한다.
// 아래 메서드들은 '메서드 이름 규칙'만으로 스프링 Data JPA가 쿼리를 자동으로 만들어준다.
@Repository
public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {
  List<CouponIssue> findCouponIssueByUserId(Long userId);
  // 사용 가능한(미사용 + 미만료) 쿠폰만 조회: isActive=true 이고 deletedAt 이 현재시각 이후
  List<CouponIssue> findByUserIdAndIsActiveTrueAndDeletedAtAfter(Long userId, LocalDateTime currentDate);
  Optional<CouponIssue> findByCouponIdAndUserId(Long couponId, Long userId);
  List<CouponIssue> findByCouponIdInAndUserId(List<Long> couponId, Long userId);

  // 발급자 SET 복원용. 사용 완료(isActive=false) 행도 포함한다
  // 이 저장소는 "한 사용자가 같은 쿠폰을 다시 받을 수 없다"를 전제하고, uq_coupon_issue_user 도 같은 범위다
  @Query("SELECT ci.userId FROM CouponIssue ci WHERE ci.coupon.id = :couponId")
  List<Long> findUserIdsByCouponId(@Param("couponId") Long couponId);

  // 보정 배치의 대조식에 쓰는 확정 발급 수
  long countByCoupon_Id(Long couponId);

  // 발급 전략이 중복 요청을 가려내는 데 쓴다
  // 행 잠금을 잡은 뒤에 불러야 한다 - 잠금 밖에서 부르면 둘이 함께 "없음"을 보고 둘 다 넣는다
  boolean existsByCoupon_IdAndUserId(Long couponId, Long userId);

  // 계약 테스트가 1인 1매를 확인하는 데 쓴다
  long countByCoupon_IdAndUserId(Long couponId, Long userId);

  // 테스트 정리용. 자기가 만든 쿠폰의 발급 이력만 지우려는 것이다
  List<CouponIssue> findByCoupon_Id(Long couponId);
}
