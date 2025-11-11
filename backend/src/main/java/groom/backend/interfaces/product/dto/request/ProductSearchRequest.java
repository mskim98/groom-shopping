package groom.backend.interfaces.product.dto.request;

import groom.backend.domain.product.model.criteria.ProductSearchCondition;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.enums.ProductStatus;
import groom.backend.domain.product.model.vo.Price;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Sort;

@Schema(description = "제품 생성 요청 DTO")
public class ProductSearchRequest {
  // 검색 조건
  String name;

  Integer minPrice;
  Integer maxPrice;

  // 필터링 조건
  ProductStatus status; // 제품 판매 삳태
  ProductCategory category; // 제품 유형

  // 정렬 조건
  Sort.Direction nameSortDirection; // ㄱㄴㄷ, a b c... 정렬
  Sort.Direction priceSortDirection; // 가격 정렬

  public ProductSearchCondition toCreteria() {
    return ProductSearchCondition.builder()
            .name(name)
            .minPrice(minPrice)
            .maxPrice(maxPrice)
            .status(status)
            .category(category)
            .nameSortDirection(nameSortDirection)
            .priceSortDirection(priceSortDirection)
            .build();
  }
}
