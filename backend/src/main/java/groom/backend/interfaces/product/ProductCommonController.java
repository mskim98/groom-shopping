package groom.backend.interfaces.product;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.service.ProductCommonService;
import groom.backend.interfaces.product.dto.request.CreateProductRequest;
import groom.backend.interfaces.product.dto.request.UpdateProductRequest;
import groom.backend.interfaces.product.dto.response.ProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
@Tag(name = "Product Management", description = "제품 관리 API (생성, 수정, 삭제, 재고 관리)")
@SecurityRequirement(name = "JWT")
public class ProductCommonController {

    private final ProductCommonService productService;

    @Operation(
            summary = "제품 생성",
            description = "새로운 제품을 생성합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "제품 생성 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다.")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ProductResponse> createProduct(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "제품 생성 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = CreateProductRequest.class))
            )
            @Valid @RequestBody CreateProductRequest request) {

        Product product = productService.createProduct(request);
        ProductResponse productResponse = ProductResponse.from(product);

        return ResponseEntity.ok(productResponse);
    }

    @Operation(
            summary = "제품 수정",
            description = "기존 제품의 정보를 수정합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "제품 수정 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "제품을 찾을 수 없음")
    })
    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @Parameter(description = "제품 ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "제품 수정 요청",
                    required = true,
                    content = @Content(schema = @Schema(implementation = UpdateProductRequest.class))
            )
            @Valid @RequestBody UpdateProductRequest request) {

        Product product = productService.updateProduct(id, request);
        ProductResponse productResponse = ProductResponse.from(product);

        return ResponseEntity.ok(productResponse);
    }

    @Operation(
            summary = "재고 증가",
            description = "제품의 재고를 증가시킵니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "재고 증가 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "제품을 찾을 수 없음")
    })
    @PatchMapping("/{id}/stock/increase")
    public ResponseEntity<ProductResponse> increaseStock(
            @Parameter(description = "제품 ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID id,
            @Parameter(description = "증가할 수량", required = true, example = "10")
            @RequestParam Integer amount) {

        Product product = productService.increaseStock(id, amount);
        ProductResponse productResponse = ProductResponse.from(product);
        return ResponseEntity.ok(productResponse);
    }

    @Operation(
            summary = "재고 감소",
            description = "제품의 재고를 감소시킵니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "재고 감소 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (재고 부족 등)"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "제품을 찾을 수 없음")
    })
    @PatchMapping("/{id}/stock/decrease")
    public ResponseEntity<ProductResponse> decreaseStock(
            @Parameter(description = "제품 ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID id,
            @Parameter(description = "감소할 수량", required = true, example = "5")
            @RequestParam Integer amount) {

        Product product = productService.decreaseStock(id, amount);
        ProductResponse productResponse = ProductResponse.from(product);
        return ResponseEntity.ok(productResponse);
    }

    @Operation(
            summary = "제품 삭제",
            description = "제품을 삭제합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "제품 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패 - JWT 토큰이 필요합니다."),
            @ApiResponse(responseCode = "404", description = "제품을 찾을 수 없음")
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<Void> deleteProduct(
            @Parameter(description = "제품 ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok().build();
    }
}
