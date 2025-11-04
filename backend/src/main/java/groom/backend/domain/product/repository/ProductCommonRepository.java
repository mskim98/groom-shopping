package groom.backend.domain.product.repository;

import groom.backend.domain.product.model.Product;

import java.util.Optional;
import java.util.UUID;

public interface ProductCommonRepository {
    Optional<Product> findById(UUID id);
    Product save(Product product);
    void deleteById(UUID id);
}
