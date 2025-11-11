package groom.backend.interfaces.product;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.service.ProductQueryService;
import groom.backend.interfaces.product.dto.request.ProductSearchRequest;
import groom.backend.interfaces.product.dto.response.ProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/product")
@RequiredArgsConstructor
@Tag(name = "Product Query", description = "제품 조회 API")
@SecurityRequirement(name = "JWT")
public class ProductQueryController {

    private final ProductQueryService productQueryService;

    @Operation(
            summary = "제품 목록 조회",
            description = "페이징 처리된 제품 목록을 조회하여 Page 형태로 반환합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "제품 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @GetMapping
    public ResponseEntity<Page<ProductResponse>> findAllProducts(
            @Parameter(description = "페이징 정보", example = "page=0&size=20&sort=id,DESC")
            @PageableDefault(
                    size = 20,
                    sort = "id",
                    direction = Sort.Direction.DESC
            ) Pageable pageable) {

        Page<Product> productPage = productQueryService.findAllProducts(pageable);

        Page<ProductResponse> products = productPage.map(ProductResponse::from);

        return ResponseEntity.ok(products);
    }

    @Operation(
            summary = "제품 상세 조회",
            description = "제품 ID로 제품 상세 정보를 조회합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "제품 조회 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "제품을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> findById(
            @Parameter(description = "제품 ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID id) {
        Product product = productQueryService.findById(id);

        ProductResponse productResponse = ProductResponse.from(product);

        return ResponseEntity.ok(productResponse);
    }

    @Operation(
            summary = "제품 조건 검색",
            description = "제품의 조건, 필터링 및 정렬 기능을 사용하여 검색하여 Page 형태로 반환합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "제품 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @GetMapping("/search")
    public ResponseEntity<Page<ProductResponse>> findByCondition(
            @ModelAttribute ProductSearchRequest request, Pageable pageable) {
        Page<Product> products = productQueryService.findByCondition(request, pageable);

        Page<ProductResponse> productResponses =  products.map(ProductResponse::from);
        return ResponseEntity.ok(productResponses);
    }
}
