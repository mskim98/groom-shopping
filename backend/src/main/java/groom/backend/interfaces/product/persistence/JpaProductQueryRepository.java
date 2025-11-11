package groom.backend.interfaces.product.persistence;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.criteria.ProductSearchCondition;
import groom.backend.domain.product.repository.ProductQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaProductQueryRepository implements ProductQueryRepository {

    @PersistenceContext
    private final EntityManager em;

    private final JpaProductRepository jpaProductRepository;
    private final SpringDataProductRepository springDataProductRepository;

    public JpaProductQueryRepository(JpaProductRepository jpaProductRepository, SpringDataProductRepository springDataProductRepository, EntityManager em) {
        this.jpaProductRepository = jpaProductRepository;
        this.springDataProductRepository = springDataProductRepository;
        this.em = em;
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

    /**
     * 검색 조건에 맞춰 검색/정렬/필터링 기능을 제공합니다.
     * @param condition
     * @param pageable
     * @return
     */
    @Override
    public Page<Product> findByCondition(ProductSearchCondition condition, Pageable pageable) {
      // Criteria Builder를 이용하여 Type-Safe하게 조건 검색함.

      CriteriaBuilder cb = em.getCriteriaBuilder();
      CriteriaQuery<ProductJpaEntity> cq = cb.createQuery(ProductJpaEntity.class);
      Root<ProductJpaEntity> product = cq.from(ProductJpaEntity.class);

      List<Predicate> predicates = new ArrayList<>();

      // 검색 조건
      // 검색 단어가 포함된 이름을 검색
      if (condition.getName() != null && !condition.getName().isBlank()) {
        predicates.add(cb.like(cb.lower(product.get("name")), "%" + condition.getName().toLowerCase() + "%"));
      }

      // 가격 범위
      if (condition.getMinPrice() != null) {
        predicates.add(cb.greaterThanOrEqualTo(product.get("price").get("amount"), condition.getMinPrice()));
      }
      if (condition.getMaxPrice() != null) {
        predicates.add(cb.lessThanOrEqualTo(product.get("price").get("amount"), condition.getMaxPrice()));
      }

      cq.where(predicates.toArray(new Predicate[0]));

      // 필터링 조건
      // 상태
      if (condition.getStatus() != null) {
        predicates.add(cb.equal(product.get("status"), condition.getStatus()));
      }

      // 카테고리
      if (condition.getCategory() != null) {
        predicates.add(cb.equal(product.get("category"), condition.getCategory()));
      }

      cq.where(predicates.toArray(new Predicate[0]));

      // 정렬 조건
      List<Order> orders = new ArrayList<>();
      Sort.Direction nameDir = condition.getNameSortDirection();
      Sort.Direction priceDir = condition.getPriceSortDirection();

      if (nameDir != null) {
        orders.add(nameDir.isAscending() ? cb.asc(product.get("name")) : cb.desc(product.get("name")));
      }

      if (priceDir != null) {
        orders.add(priceDir.isAscending() ? cb.asc(product.get("price").get("amount")) : cb.desc(product.get("price").get("amount")));
      }

      // 기본 정렬 (id desc)
      if (orders.isEmpty()) {
        orders.add(cb.desc(product.get("id")));
      }

      cq.orderBy(orders);

      // 실제 데이터 쿼리 실행
      TypedQuery<ProductJpaEntity> query = em.createQuery(cq);
      query.setFirstResult((int) pageable.getOffset());
      query.setMaxResults(pageable.getPageSize());
      List<ProductJpaEntity> content = query.getResultList();

      // count 쿼리
      CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
      Root<ProductJpaEntity> countRoot = countQuery.from(ProductJpaEntity.class);
      countQuery.select(cb.count(countRoot))
              .where(predicates.toArray(new Predicate[0]));
      Long total = em.createQuery(countQuery).getSingleResult();

      // Page 반환
      return new PageImpl<>(content, pageable, total)
              .map(e -> {
                try {
                  return jpaProductRepository.toDomain(e);
                } catch (Exception ex) {
                  throw new RuntimeException("Failed to convert ProductJpaEntity to Product", ex);
                }
              });
    }
}

