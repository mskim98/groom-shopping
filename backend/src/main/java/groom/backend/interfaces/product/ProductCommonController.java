package groom.backend.interfaces.product;

import groom.backend.common.response.ApiResponse;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.service.ProductCommonService;
import groom.backend.interfaces.product.dto.request.CreateProductRequest;
import groom.backend.interfaces.product.dto.request.UpdateProductRequest;
import groom.backend.interfaces.product.dto.response.ProductResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
public class ProductCommonController {

    private final ProductCommonService productService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductResponse> createProduct(
            @Valid @RequestBody CreateProductRequest request) {

        Product product = productService.createProduct(request);
        ProductResponse productResponse = ProductResponse.from(product);

        return ApiResponse.success(productResponse, "상품이 정상적으로 등록되었습니다.");
    }

    @PatchMapping("/{id}")
    public ApiResponse<ProductResponse> updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequest request) {

        Product product = productService.updateProduct(id, request);
        ProductResponse productResponse = ProductResponse.from(product);

        return ApiResponse.success(productResponse, "상품이 수정되었습니다.");
    }

    @PatchMapping("/{id}/stock/increase")
    public ApiResponse<ProductResponse> increaseStock(
            @PathVariable UUID id,
            @RequestParam Integer amount) {

        Product product = productService.increaseStock(id, amount);
        ProductResponse productResponse = ProductResponse.from(product);
        return ApiResponse.success(productResponse, "재고가 증가되었습니다.");
    }

    @PatchMapping("/{id}/stock/decrease")
    public ApiResponse<ProductResponse> decreaseStock(
            @PathVariable UUID id,
            @RequestParam Integer amount) {

        Product product = productService.decreaseStock(id, amount);
        ProductResponse productResponse = ProductResponse.from(product);
        return ApiResponse.success(productResponse, "재고가 감소되었습니다.");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
        return ApiResponse.success(null, "상품이 삭제되었습니다.");
    }
}
