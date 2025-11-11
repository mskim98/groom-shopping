package groom.backend.domain.product.service;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductQueryRepository;
import groom.backend.interfaces.product.dto.request.ProductSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductQueryRepository productQueryRepository;

    public Page<Product> findAllProducts(Pageable pageable) {
        return productQueryRepository.findAll(pageable);
    }

    public Product findById(UUID id) {
        return productQueryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "존재하지 않는 상품입니다."));
    }

    public Page<Product> findByCondition(ProductSearchRequest request, Pageable pageable) {
        return productQueryRepository.findByCondition(request.toCreteria(), pageable);
    }
}
