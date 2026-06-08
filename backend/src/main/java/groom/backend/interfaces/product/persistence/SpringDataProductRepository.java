package groom.backend.interfaces.product.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataProductRepository extends JpaRepository<ProductJpaEntity, UUID> {
    List<ProductJpaEntity> findByIdIn(List<UUID> ids);

    // 키셋(seek) 페이지네이션용: 마지막으로 처리한 id 이후를 id 오름차순으로 size 만큼 가져온다.
    // 깊은 OFFSET 없이 일정 메모리로 전체 상품을 순회(부팅 시 재고 워밍업)할 때 사용.
    List<ProductJpaEntity> findByIdGreaterThanOrderByIdAsc(UUID id, Pageable pageable);
}



