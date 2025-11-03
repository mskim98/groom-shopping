package groom.backend.domain.product.service;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.vo.Description;
import groom.backend.domain.product.model.vo.Name;
import groom.backend.domain.product.model.vo.Price;
import groom.backend.domain.product.repository.ProductCommonRepository;
import groom.backend.interfaces.product.dto.request.CreateProductRequest;
import groom.backend.interfaces.product.dto.request.UpdateProductRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductCommonService {

    private final ProductCommonRepository productCommonRepository;

    @Transactional
    public Product findById(Long id) {
        return productCommonRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "존재하지 않는 상품입니다."));
    }

    @Transactional
    public Product createProduct(CreateProductRequest request) {

        Product product = request.toEntity();

        return productCommonRepository.save(product);
    }

    @Transactional
    public Product updateProduct(Long id, UpdateProductRequest request) {
        Product product = findById(id);

        if (request.name() != null) {
            product.changeName(new Name(request.name()));
        }
        if (request.description() != null) {
            product.changeDescription(new Description(request.description()));
        }
        if (request.price() != null) {
            product.changePrice(new Price(request.price()));
        }
        if (request.stock() != null) {
            int diff = request.stock() - product.getStock().getAmount();
            if (diff > 0) {
                product.increaseStock(diff);
            } else if (diff < 0) {
                product.decreaseStock(-diff);
            }
        }
        if (request.category() != null) {
            product.changeCategory(request.category());
        }

        productCommonRepository.save(product);

        return product;
    }

    @Transactional
    public Product increaseStock(Long id, Integer amount) {
        System.out.println("서비스 : " + amount);
        Product product = findById(id);
        product.increaseStock(amount);
        productCommonRepository.save(product);
        return product;
    }

    @Transactional
    public Product decreaseStock(Long id, int amount) {
        Product product = findById(id);
        product.decreaseStock(amount);
        productCommonRepository.save(product);
        return product;
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = findById(id);
        productCommonRepository.delete(product);
    }
}

