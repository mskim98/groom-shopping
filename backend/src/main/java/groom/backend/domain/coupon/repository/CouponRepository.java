package groom.backend.domain.coupon.repository;

import groom.backend.interfaces.coupon.dto.request.CouponSearchCondition;
import groom.backend.domain.coupon.model.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// @Repository : JPA 기반 쿠폰 영속성 계층. 기본 CRUD는 JpaRepository가 제공한다.
@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {
  // @Lock(PESSIMISTIC_WRITE) : 조회하는 동안 해당 행에 DB 쓰기 락을 건다(SELECT … FOR UPDATE).
  // Redis 장애 때의 DB 경로가 이 잠금 아래서 quantity 와 발급 수를 비교한다.
  // @Query : 메서드 이름 규칙 대신 직접 JPQL을 지정한다. @Param 은 쿼리의 :couponId 자리에 인자를 바인딩.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM Coupon c WHERE c.id = :couponId")
  Optional<Coupon> findByIdForUpdate(@Param("couponId") Long couponId);

  // @Modifying : SELECT가 아닌 UPDATE/DELETE 쿼리임을 알려준다(이게 없으면 실행되지 않음).
  @Modifying
  @Query("UPDATE Coupon c SET c.isActive = false where c.id = :couponId")
  Integer updateIsActiveFalse(@Param("couponId") Long couponId);

  // 마감 시 확정 발급 수를 한 번 굳힌다. 이후로는 변하지 않으므로 리포트가 매번 COUNT 하지 않는다
  // 엔티티를 읽어 세터로 넣지 않는 이유는 이 시점에 quantity 를 함께 덮어쓸 위험을 없애기 위해서다 -
  // quantity 는 S2 이후 불변 발행 수량이고, 그 불변성이 초과 발급 판정의 유일한 기준선이다
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Coupon c SET c.issuedCount = :issuedCount WHERE c.id = :couponId")
  int freezeIssuedCount(@Param("couponId") Long couponId, @Param("issuedCount") Long issuedCount);

  // 부팅 워밍업용 키셋(seek) 페이지네이션. id 오름차순으로 활성 쿠폰만 훑는다.
  // OFFSET 방식과 달리 페이지가 뒤로 갈수록 느려지지 않고, 영속성 컨텍스트에 전량을 올리지 않는다.
  @Query("SELECT c FROM Coupon c WHERE c.id > :lastId AND c.isActive = true ORDER BY c.id ASC")
  List<Coupon> findActiveByIdGreaterThan(@Param("lastId") Long lastId, Pageable pageable);

  // 보정 배치용 전수 스캔. 워밍업과 달리 종료(isActive=false)·만료 쿠폰도 봐야 고아 예약을 회수할 수 있다
  @Query("SELECT c FROM Coupon c WHERE c.id > :lastId ORDER BY c.id ASC")
  List<Coupon> findAllByIdGreaterThanOrderById(@Param("lastId") Long lastId, Pageable pageable);

  @Query("""
  SELECT c
  FROM Coupon c
  WHERE
    (:#{#condition.name} IS NULL OR c.name LIKE %:#{#condition.name}%)
    AND (:#{#condition.description} IS NULL OR c.description LIKE %:#{#condition.description}%)
    AND (:#{#condition.type} IS NULL OR c.type = :#{#condition.type})
    AND (:#{#condition.isActive} IS NULL OR c.isActive = :#{#condition.isActive})
    AND (:#{#condition.expireDateFrom} IS NULL OR c.expireDate >= :#{#condition.expireDateFrom})
    AND (:#{#condition.expireDateTo} IS NULL OR c.expireDate <= :#{#condition.expireDateTo})
    AND (:#{#condition.minQuantity} IS NULL OR c.quantity >= :#{#condition.minQuantity})
    AND (:#{#condition.maxQuantity} IS NULL OR c.quantity <= :#{#condition.maxQuantity})
    AND (:#{#condition.minAmount} IS NULL OR c.amount >= :#{#condition.minAmount})
    AND (:#{#condition.maxAmount} IS NULL OR c.amount <= :#{#condition.maxAmount})
  """)
  Page<Coupon> searchByCondition(@Param("condition") CouponSearchCondition condition, Pageable pageable);
}
