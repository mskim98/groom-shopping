package groom.backend.domain.order.repository;

import groom.backend.domain.order.model.Order;
import io.lettuce.core.dynamic.annotation.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// JpaRepository<Order, UUID> : 키 타입이 UUID인 Order의 기본 CRUD를 스프링이 자동 구현.
public interface OrderRepository extends JpaRepository<Order, UUID> {

    // JOIN FETCH : 주문과 주문항목을 한 번의 쿼리로 함께 가져온다.
    // (없으면 항목을 쓸 때마다 쿼리가 추가로 나가는 N+1 문제가 발생)
    @Query("SELECT o FROM Order o JOIN FETCH o.orderItems WHERE o.id = :orderId")
    Optional<Order> findByIdWithItems(@Param("orderId") UUID orderId);

    // 메서드 이름 규칙: userId로 조회하고 createdAt 내림차순 정렬 → 쿼리를 스프링이 자동 생성.
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT o FROM Order o JOIN FETCH o.orderItems WHERE o.userId = :userId ORDER BY o.createdAt DESC")
    List<Order> findByUserIdWithItemsOrderByCreatedAtDesc(@Param("userId") Long userId);
}
