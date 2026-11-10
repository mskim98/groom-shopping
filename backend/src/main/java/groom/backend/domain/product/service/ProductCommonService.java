package groom.backend.domain.product.service;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.vo.Description;
import groom.backend.domain.product.model.vo.Name;
import groom.backend.domain.product.model.vo.Price;
import groom.backend.domain.product.repository.ProductCommonRepository;
import groom.backend.infrastructure.aws.S3Service;
import groom.backend.interfaces.product.dto.request.CreateProductRequest;
import groom.backend.interfaces.product.dto.request.UpdateProductRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductCommonService {

    private final ProductCommonRepository productCommonRepository;
    private final S3Service s3Service;

    @Transactional
    public Product findById(UUID id) {
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
    public Product createProduct(CreateProductRequest request, MultipartFile image) {

        Product product = request.toEntity();

        // 이미지가 있다면 S3 업로드
        if (image != null && !image.isEmpty()) {
            String imageUrl = s3Service.upload(image);
            product.changeImageUrl(imageUrl);
        }

        return productCommonRepository.save(product);
    }

    @Transactional
    public Product updateProduct(UUID id, UpdateProductRequest request) {
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
            rejectIfFlashSale(product);
            int diff = request.stock() - product.getStock();
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
    public Product increaseStock(UUID id, Integer amount) {
        Product product = findById(id);
        rejectIfFlashSale(product);
        product.increaseStock(amount);
        productCommonRepository.save(product);
        return product;
    }

    @Transactional
    public Product decreaseStock(UUID id, int amount) {
        Product product = findById(id);
        rejectIfFlashSale(product);
        product.decreaseStock(amount);
        productCommonRepository.save(product);
        return product;
    }

    // 플래시 세일 상품의 재고는 Redis 선점 + 원장이 정한다. 관리자가 product.stock 을 바꾸면 남은 재고(stock − 미적용 합)와 Redis 키가 어긋난다
    private void rejectIfFlashSale(Product product) {
        if (product.isFlashSale()) {
            throw new BusinessException(ErrorCode.CONFLICT, "플래시 세일 상품의 재고는 직접 바꿀 수 없습니다.");
        }
    }

    @Transactional
    public void deleteProduct(UUID id) {
        productCommonRepository.deleteById(id);
    }
}

