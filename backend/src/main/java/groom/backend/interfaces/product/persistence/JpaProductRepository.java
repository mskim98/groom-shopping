package groom.backend.interfaces.product.persistence;

import groom.backend.domain.product.entity.Product;
import groom.backend.domain.product.repository.ProductRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JpaProductRepository implements ProductRepository {

    private final SpringDataProductRepository springRepo;
    private final SpringDataCartRepository cartRepo;

    public JpaProductRepository(SpringDataProductRepository springRepo, SpringDataCartRepository cartRepo) {
        this.springRepo = springRepo;
        this.cartRepo = cartRepo;
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return springRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Product save(Product product) {
        ProductJpaEntity saved = springRepo.save(toEntity(product));
        return toDomain(saved);
    }

    @Override
    public List<Product> findByIds(List<UUID> productIds) {
        return springRepo.findByIdIn(productIds).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Product> findProductsInCartByUserId(Long userId) {
        List<UUID> productIds = cartRepo.findProductIdsByUserId(userId);
        return findByIds(productIds);
    }

    private Product toDomain(ProductJpaEntity e) {
        return new Product(
                e.getId(),
                e.getName(),
                e.getDescription(),
                e.getPrice(),
                e.getStock(),
                e.getThresholdValue(),
                e.getIsActive(),
                e.getCategory()
        );
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
                .category(p.getCategory())
                .build();
    }
}

