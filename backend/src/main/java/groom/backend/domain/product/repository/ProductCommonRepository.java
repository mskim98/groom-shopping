package groom.backend.domain.product.repository;

import groom.backend.domain.product.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductCommonRepository extends JpaRepository<Product, Long> {
}
