package groom.backend.domain.product.model.criteria;

import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.enums.ProductStatus;
import lombok.*;
import org.springframework.data.domain.Sort;

/**
 * 상품 검색/정렬/필터링 정보를 제공하는 criteria
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSearchCondition {
  // 검색 조건
  private String name;

  // Price vo가 유효성 검증하기 때문에 null이 들어가면 안됨.
  // Argument Exception 발생가능하여 타입 수정
  private Integer minPrice;
  private Integer maxPrice;

  // 필터링 조건
  private ProductStatus status; // 제품 판매 삳태
  private ProductCategory category; // 제품 유형

  // 정렬 조건
  private Sort.Direction nameSortDirection; // ㄱㄴㄷ, a b c... 정렬
  private Sort.Direction priceSortDirection; // 가격 정렬
}
