package groom.backend.interfaces.product.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 차감 1회가 내는 SQL 문 수를 고정한다. 최소값은 SELECT 1 + UPDATE 1 = 2문이다.
 *
 * <p>{@code save()} 가 detached 엔티티를 넘겨 {@code em.merge()} 를 타지만 <b>추가 SELECT 는 없다.</b>
 * 같은 트랜잭션에서 {@code findById} 가 이미 엔티티를 영속성 컨텍스트에 올려 뒀고,
 * {@code merge} 는 식별자로 컨텍스트를 먼저 뒤져 찾으면 DB 를 다시 조회하지 않기 때문이다
 * ({@code ProductStockService.decreaseOnce} 가 findById → decreaseStock → save 순서다).
 *
 * <p>이 테스트가 지키는 것 : 차감 경로에 조회가 하나 더 끼어드는 변경을 막는다.
 * 예를 들어 {@code decreaseOnce} 밖에서 상품을 다시 읽거나, {@code save} 를 트랜잭션 밖으로
 * 옮기면 merge 가 컨텍스트에서 못 찾아 SELECT 를 한 번 더 낸다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@DisplayName("재고 저장 SQL 문 수")
class JpaProductRepositorySaveStatementCountTest {

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SpringDataProductRepository springDataProductRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private UUID productId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        springDataProductRepository.save(ProductJpaEntity.builder()
                .id(productId).name("stmt-count").description("d").price(1000).stock(100)
                .isActive(true).category("GENERAL").status("AVAILABLE").thresholdValue(10)
                .build());
    }

    @AfterEach
    void tearDown() {
        springDataProductRepository.deleteById(productId);
    }

    @Test
    @Transactional
    @DisplayName("같은 트랜잭션의 findById 후 save 는 SELECT 1 + UPDATE 1 = 2문만 낸다")
    void findThenSaveIssuesTwoStatements() {
        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Product product = productRepository.findById(productId).orElseThrow();
        product.decreaseStock(1);
        productRepository.save(product);
        entityManager.flush();

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }
}
