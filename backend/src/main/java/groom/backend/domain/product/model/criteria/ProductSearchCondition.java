package groom.backend.domain.product.model.criteria;

import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.enums.ProductStatus;
import groom.backend.domain.product.model.vo.Price;
import org.springframework.data.domain.Sort;

/**
 * 상품 검색/정렬/필터링 정보를 제공하는 criteria
 */
public class ProductSearchCondition {
  // 검색 조건
  private String name;

  private Price minPrice;
  private Price maxPrice;

  // 필터링 조건
  private ProductStatus status; // 제품 판매 삳태
  private ProductCategory category; // 제품 유형

  // 정렬 조건
  private Sort.Direction nameSortDirection; // ㄱㄴㄷ, a b c... 정렬
  private Sort.Direction priceSortDirection; // 가격 정렬
}
