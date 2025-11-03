package groom.backend.domain.product.repository;

import groom.backend.domain.product.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductQueryRepository extends JpaRepository<Product, Long> {

//    // 카테고리별 조회
//    Page<Product> findByCategory(ProductCategory category, Pageable pageable);
//
//    // 상태별 조회
//    Page<Product> findByStatus(ProductStatus status, Pageable pageable);
//
//    // 카테고리 + 상태 조회
//    Page<Product> findByCategoryAndStatus(ProductCategory category,
//                                          ProductStatus status,
//                                          Pageable pageable);
//
//    // 상품명 검색 (like)
//    @Query("SELECT p FROM Product p WHERE p.name.value LIKE %:keyword%")
//    Page<Product> findByNameContaining(@Param("keyword") String keyword,
//                                       Pageable pageable);
//
//    // 가격 범위 검색
//    @Query("SELECT p FROM Product p WHERE p.price.amount BETWEEN :minPrice AND :maxPrice")
//    Page<Product> findByPriceRange(@Param("minPrice") Integer minPrice,
//                                   @Param("minPrice") Integer maxPrice,
//                                   Pageable pageable);
//
//    // 재고 부족 상품 조회 (재고 10개 미만)
//    @Query("SELECT p FROM Product p WHERE p.stock.quantity < 10 AND p.status = 'AVAILABLE'")
//    List<Product> findLowStockProducts();
}
