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
  // @Lock(PESSIMISTIC_WRITE) : 조회하는 동안 해당 행에 DB 쓰기 락을 걸어
  // 다른 트랜잭션이 동시에 수량을 못 바꾸게 한다(Redis 재고가 없을 때의 폴백 경로에서 사용).
  // @Query : 메서드 이름 규칙 대신 직접 JPQL을 지정한다. @Param 은 쿼리의 :couponId 자리에 인자를 바인딩.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM Coupon c WHERE c.id = :couponId")
  Optional<Coupon> findByIdForUpdate(@Param("couponId") Long couponId);

  // @Modifying : SELECT가 아닌 UPDATE/DELETE 쿼리임을 알려준다(이게 없으면 실행되지 않음).
  @Modifying
  @Query("UPDATE Coupon c SET c.isActive = false where c.id = :couponId")
  Integer updateIsActiveFalse(@Param("couponId") Long couponId);

  // 수량 차감을 읽고-쓰기가 아니라 단일 UPDATE 로 수행한다.
  // 조회 후 엔티티에서 빼면 두 요청이 같은 값을 읽어 한 번만 줄어드는 lost update 가 생기는데,
  // DB 가 quantity 를 직접 감산하면 그 창 자체가 없어져 락도 재시도도 필요하지 않다.
  // quantity > 0 조건이 음수 방지 겸 소진 감지를 겸한다(0건이면 호출부가 소진으로 처리).
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE Coupon c SET c.quantity = c.quantity - 1 WHERE c.id = :couponId AND c.quantity > 0")
  int decreaseQuantityAtomically(@Param("couponId") Long couponId);

  // 부팅 워밍업용 키셋(seek) 페이지네이션. id 오름차순으로 활성 쿠폰만 훑는다.
  // OFFSET 방식과 달리 페이지가 뒤로 갈수록 느려지지 않고, 영속성 컨텍스트에 전량을 올리지 않는다.
  @Query("SELECT c FROM Coupon c WHERE c.id > :lastId AND c.isActive = true ORDER BY c.id ASC")
  List<Coupon> findActiveByIdGreaterThan(@Param("lastId") Long lastId, Pageable pageable);

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
