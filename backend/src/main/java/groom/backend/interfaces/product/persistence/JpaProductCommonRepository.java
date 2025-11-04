package groom.backend.interfaces.product.persistence;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductCommonRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaProductCommonRepository implements ProductCommonRepository {

    private final JpaProductRepository jpaProductRepository;
    private final SpringDataProductRepository springDataProductRepository;

    public JpaProductCommonRepository(JpaProductRepository jpaProductRepository, SpringDataProductRepository springDataProductRepository) {
        this.jpaProductRepository = jpaProductRepository;
        this.springDataProductRepository = springDataProductRepository;
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return jpaProductRepository.findById(id);
    }

    @Override
    public Product save(Product product) {
        return jpaProductRepository.save(product);
    }

    @Override
    public void deleteById(UUID id) {
        springDataProductRepository.deleteById(id);
    }
}

