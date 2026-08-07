package groom.backend.interfaces.product.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataProductRepository extends JpaRepository<ProductJpaEntity, UUID> {
    List<ProductJpaEntity> findByIdIn(List<UUID> ids);

    // 키셋(seek) 페이지네이션용: 마지막으로 처리한 id 이후를 id 오름차순으로 size 만큼 가져온다.
    // 깊은 OFFSET 없이 일정 메모리로 전체 상품을 순회(부팅 시 재고 워밍업)할 때 사용.
    List<ProductJpaEntity> findByIdGreaterThanOrderByIdAsc(UUID id, Pageable pageable);

    // 조건부 UPDATE: 재고가 충분할 때만 차감한다. 영향 행 수 0 = 재고 부족
    // version 을 함께 올려 낙관적 락 경로(복원 등)와 섞여도 stale write 가 나지 않게 한다
    // COALESCE: V12 이전에 만들어진 행은 version 이 NULL 일 수 있고, NULL + 1 은 NULL 이라 @Version 이 깨진다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProductJpaEntity p SET p.stock = p.stock - :quantity, p.version = COALESCE(p.version, 0L) + 1 "
            + "WHERE p.id = :id AND p.stock >= :quantity")
    int decreaseStockIfEnough(@Param("id") UUID id, @Param("quantity") int quantity);

    // 비관적 락: 행 락을 먼저 잡고 읽는다. 경합 시 대기하므로 충돌 자체가 발생하지 않는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductJpaEntity p WHERE p.id = :id")
    Optional<ProductJpaEntity> findByIdForUpdate(@Param("id") UUID id);
}
