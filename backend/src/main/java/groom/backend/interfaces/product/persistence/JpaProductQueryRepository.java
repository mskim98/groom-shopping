package groom.backend.interfaces.product.persistence;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductQueryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaProductQueryRepository implements ProductQueryRepository {

    private final JpaProductRepository jpaProductRepository;
    private final SpringDataProductRepository springDataProductRepository;

    public JpaProductQueryRepository(JpaProductRepository jpaProductRepository, SpringDataProductRepository springDataProductRepository) {
        this.jpaProductRepository = jpaProductRepository;
        this.springDataProductRepository = springDataProductRepository;
    }

    @Override
    public Page<Product> findAll(Pageable pageable) {
        return springDataProductRepository.findAll(pageable)
                .map(e -> {
                    try {
                        return jpaProductRepository.toDomain(e);
                    } catch (Exception ex) {
                        throw new RuntimeException("Failed to convert ProductJpaEntity to Product", ex);
                    }
                });
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return jpaProductRepository.findById(id);
    }
}

