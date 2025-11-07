package groom.backend.domain.product.repository;

import groom.backend.domain.product.model.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {
    Optional<Product> findById(UUID id);

    Product save(Product product);

    List<Product> findByIds(List<UUID> productIds);

    List<Product> findProductsInCartByUserId(Long userId);
}


