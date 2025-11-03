package groom.backend.interfaces.product;

import groom.backend.common.response.ApiResponse;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.service.ProductQueryService;
import groom.backend.interfaces.product.dto.response.ProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/product")
@RequiredArgsConstructor
public class ProductQueryController {

    private final ProductQueryService productQueryService;

    @GetMapping
    public ApiResponse<Page<ProductResponse>> findAllProducts(
            @PageableDefault(
                    size = 20,
                    sort = "id",
                    direction = Sort.Direction.DESC
            ) Pageable pageable) {

        Page<Product> productPage = productQueryService.findAllProducts(pageable);

        Page<ProductResponse> products = productPage.map(ProductResponse::from);

        return ApiResponse.success(products);
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductResponse> findById(@PathVariable Long id) {
        Product product = productQueryService.findById(id);

        ProductResponse productResponse = ProductResponse.from(product);

        return ApiResponse.success(productResponse);
    }
}
