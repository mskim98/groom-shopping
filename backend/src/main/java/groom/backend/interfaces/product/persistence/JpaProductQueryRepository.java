package groom.backend.interfaces.product.persistence;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.model.criteria.ProductSearchCondition;
import groom.backend.domain.product.repository.ProductQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaDerivedRoot;
import org.hibernate.query.criteria.JpaSubQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Repository
public class JpaProductQueryRepository implements ProductQueryRepository {

    @PersistenceContext
    private final EntityManager em;

    private final JpaProductRepository jpaProductRepository;
    // 목록 · 검색이 세는 행의 상한. totalElements 는 min(실제 행 수, 상한)이다
    private final int countCap;

    public JpaProductQueryRepository(JpaProductRepository jpaProductRepository, EntityManager em,
                                     @Value("${product.list.count-cap:10000}") int countCap) {
        this.jpaProductRepository = jpaProductRepository;
        this.em = em;
        this.countCap = countCap;
    }

    @Override
    public Page<Product> findAll(Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ProductJpaEntity> cq = cb.createQuery(ProductJpaEntity.class);
        Root<ProductJpaEntity> product = cq.from(ProductJpaEntity.class);
        cq.orderBy(QueryUtils.toOrders(pageable.getSort(), product, cb));
        return page(cq, pageable, root -> new Predicate[0]);
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return jpaProductRepository.findById(id);
    }

    /**
     * 검색 조건에 맞춰 검색/정렬/필터링 기능을 제공합니다.
     * 본문 쿼리는 쪽 크기만큼만 읽고, 개수는 {@link #page} 가 필요할 때만 상한까지 센다
     */
    @Override
    public Page<Product> findByCondition(ProductSearchCondition condition, Pageable pageable) {
      // Criteria Builder를 이용하여 Type-Safe하게 조건 검색함.

      CriteriaBuilder cb = em.getCriteriaBuilder();
      CriteriaQuery<ProductJpaEntity> cq = cb.createQuery(ProductJpaEntity.class);
      Root<ProductJpaEntity> product = cq.from(ProductJpaEntity.class);

      Function<Root<ProductJpaEntity>, Predicate[]> where = root -> predicates(cb, root, condition);
      cq.where(where.apply(product));

      // 정렬 조건
      List<Order> orders = new ArrayList<>();
      Sort.Direction nameDir = condition.getNameSortDirection();
      Sort.Direction priceDir = condition.getPriceSortDirection();

      if (nameDir != null) {
        orders.add(nameDir.isAscending() ? cb.asc(product.get("name")) : cb.desc(product.get("name")));
      }

      if (priceDir != null) {
        orders.add(priceDir.isAscending() ? cb.asc(product.get("price")) : cb.desc(product.get("price")));
      }

      // 기본 정렬 (id desc)
      if (orders.isEmpty()) {
        orders.add(cb.desc(product.get("id")));
      }

      cq.orderBy(orders);

      return page(cq, pageable, where);
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<ProductJpaEntity> product, ProductSearchCondition condition) {
      List<Predicate> predicates = new ArrayList<>();

      // 검색 조건
      // 검색 단어가 포함된 이름을 검색
      if (condition.getName() != null && !condition.getName().isBlank()) {
        String keyword = condition.getName().toLowerCase();
        predicates.add(cb.like(cb.lower(product.get("name")), "%" + keyword + "%"));
        // 2글자 키워드는 trigram 인덱스를 못 타므로 V25 의 2-gram 인덱스로 후보를 먼저 좁힌다(LIKE 가 포함 여부를 확정한다)
        // LIKE 와일드카드(% _)가 든 키워드는 글자 그대로의 조각과 뜻이 달라 좁히지 않는다
        if (keyword.codePointCount(0, keyword.length()) == 2 && keyword.indexOf('%') < 0 && keyword.indexOf('_') < 0) {
          predicates.add(cb.isTrue(cb.function("product_name_has_gram2", Boolean.class,
                  cb.lower(product.get("name")), cb.literal(keyword))));
        }
      }

      // 가격 범위
      if (condition.getMinPrice() != null) {
        predicates.add(cb.greaterThanOrEqualTo(product.get("price"), condition.getMinPrice()));
      }
      if (condition.getMaxPrice() != null) {
        predicates.add(cb.lessThanOrEqualTo(product.get("price"), condition.getMaxPrice()));
      }

      // 필터링 조건
      // 상태
      if (condition.getStatus() != null) {
        predicates.add(cb.equal(product.get("status"), condition.getStatus().name()));
      }

      // 카테고리
      if (condition.getCategory() != null) {
        predicates.add(cb.equal(product.get("category"), condition.getCategory().name()));
      }

      return predicates.toArray(new Predicate[0]);
    }

    /**
     * 본문을 읽고 Page 로 만든다 - 상한(countCap)을 넘는 행은 읽지도 세지도 않는다:
     * 본문은 상한 안의 행만 읽고(시작 위치 + 쪽 크기가 상한을 넘으면 그만큼 줄인다), 개수는 첫 쪽이 차지 않았거나
     * 마지막 쪽이면 Spring Data 가 count 를 건너뛰며 그 밖에는 상한까지만 센다
     */
    private Page<Product> page(CriteriaQuery<ProductJpaEntity> cq, Pageable pageable,
                               Function<Root<ProductJpaEntity>, Predicate[]> where) {
      TypedQuery<ProductJpaEntity> query = em.createQuery(cq);
      long offset = pageable.getOffset();
      query.setFirstResult((int) offset);
      query.setMaxResults((int) Math.max(0, Math.min(pageable.getPageSize(), countCap - offset)));
      List<Product> content = query.getResultList().stream().map(this::toDomain).toList();

      return PageableExecutionUtils.getPage(content, pageable, () -> boundedCount(where));
    }

    // select count(id) from (select id from product where … fetch first <상한> rows only) - 조건에 맞는 행이 아무리 많아도 상한까지만 센다
    private long boundedCount(Function<Root<ProductJpaEntity>, Predicate[]> where) {
      HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();
      JpaCriteriaQuery<Long> countQuery = hcb.createQuery(Long.class);
      JpaSubQuery<UUID> capped = countQuery.subquery(UUID.class);
      Root<ProductJpaEntity> product = capped.from(ProductJpaEntity.class);
      Path<UUID> id = product.get("id");
      id.alias("id");
      capped.select(id).where(where.apply(product)).fetch(countCap);
      JpaDerivedRoot<UUID> rows = countQuery.from(capped);
      countQuery.select(hcb.count(rows.<UUID>get("id")));
      return em.createQuery(countQuery).getSingleResult();
    }

    private Product toDomain(ProductJpaEntity e) {
      try {
        return jpaProductRepository.toDomain(e);
      } catch (Exception ex) {
        throw new RuntimeException("Failed to convert ProductJpaEntity to Product", ex);
      }
    }
}
