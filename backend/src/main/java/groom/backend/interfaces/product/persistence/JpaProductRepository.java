package groom.backend.interfaces.product.persistence;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.enums.ProductCategory;
import groom.backend.domain.product.model.vo.Description;
import groom.backend.domain.product.model.vo.Name;
import groom.backend.domain.product.model.vo.Price;
import groom.backend.domain.product.model.vo.Stock;
import groom.backend.domain.product.repository.ProductRepository;
import groom.backend.interfaces.cart.persistence.SpringDataCartItemRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JpaProductRepository implements ProductRepository {

    private final SpringDataProductRepository springRepo;
    private final SpringDataCartItemRepository cartItemRepo;

    public JpaProductRepository(SpringDataProductRepository springRepo, SpringDataCartItemRepository cartItemRepo) {
        this.springRepo = springRepo;
        this.cartItemRepo = cartItemRepo;
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return springRepo.findById(id).map(this::toDomainPrivate);
    }

    @Override
    public Product save(Product product) {
        ProductJpaEntity saved = springRepo.save(toEntity(product));
        return toDomainPrivate(saved);
    }

    @Override
    public List<Product> findByIds(List<UUID> productIds) {
        return springRepo.findByIdIn(productIds).stream()
                .map(this::toDomainPrivate)
                .collect(Collectors.toList());
    }

    @Override
    public List<Product> findProductsInCartByUserId(Long userId) {
        List<UUID> productIds = cartItemRepo.findProductIdsByUserId(userId);
        return findByIds(productIds);
    }

    // 외부에서 사용할 수 있도록 public 메서드
    public Product toDomain(ProductJpaEntity e) {
        return toDomainPrivate(e);
    }

    private Product toDomainPrivate(ProductJpaEntity e) {
        try {
            ProductCategory category = e.getCategory() != null 
                    ? ProductCategory.valueOf(e.getCategory().toUpperCase()) 
                    : ProductCategory.GENERAL; // 기본값
            
            // thresholdValue가 null이면 기본값 10 설정
            Integer thresholdValue = e.getThresholdValue() != null 
                    ? e.getThresholdValue() 
                    : 10;
            
            return Product.create(
                    e.getId(),
                    new Name(e.getName() != null ? e.getName() : ""),
                    new Description(e.getDescription()),
                    new Price(e.getPrice() != null ? e.getPrice() : 0),
                    new Stock(e.getStock() != null ? e.getStock() : 0),
                    category,
                    thresholdValue,
                    e.getIsActive()
            );
        } catch (IllegalArgumentException ex) {
            // 카테고리 변환 실패 시 기본값 사용
            ProductCategory category = ProductCategory.GENERAL;
            
            // thresholdValue가 null이면 기본값 10 설정
            Integer thresholdValue = e.getThresholdValue() != null 
                    ? e.getThresholdValue() 
                    : 10;
            
            return Product.create(
                    e.getId(),
                    new Name(e.getName() != null ? e.getName() : ""),
                    new Description(e.getDescription()),
                    new Price(e.getPrice() != null ? e.getPrice() : 0),
                    new Stock(e.getStock() != null ? e.getStock() : 0),
                    category,
                    thresholdValue,
                    e.getIsActive()
            );
        }
    }

    private ProductJpaEntity toEntity(Product p) {
        return ProductJpaEntity.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .stock(p.getStock())
                .thresholdValue(p.getThresholdValue())
                .isActive(p.getIsActive())
                .category(p.getCategory() != null ? p.getCategory().name() : null)
                .status(p.getStatus() != null ? p.getStatus().name() : null)
                .build();
    }
}



