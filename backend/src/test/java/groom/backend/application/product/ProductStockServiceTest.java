package groom.backend.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.product.model.Product;
import groom.backend.domain.product.repository.ProductRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class ProductStockServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ObjectProvider<ProductStockService> selfProvider;
    @InjectMocks
    private ProductStockService productStockService;

    private final UUID productId = UUID.randomUUID();

    @Test
    void decreaseWithOptimisticLock_프록시를_거쳐_decreaseOnce에_위임한다() {
        ProductStockService proxy = mock(ProductStockService.class);
        given(selfProvider.getObject()).willReturn(proxy);
        given(proxy.decreaseOnce(productId, 2)).willReturn(7);

        int remaining = productStockService.decreaseWithOptimisticLock(productId, 2);

        assertThat(remaining).isEqualTo(7);
        then(proxy).should().decreaseOnce(productId, 2);
    }

    @Test
    void decreaseOnce_재고를_차감하고_차감후_재고를_반환한다() {
        Product product = mock(Product.class);
        given(productRepository.findById(productId)).willReturn(Optional.of(product));
        given(productRepository.save(product)).willReturn(product);
        given(product.getStock()).willReturn(8);

        int remaining = productStockService.decreaseOnce(productId, 2);

        then(product).should().decreaseStock(2);
        assertThat(remaining).isEqualTo(8);
    }

    @Test
    void recover_재시도_소진시_재고_충돌_예외로_변환한다() {
        assertThatThrownBy(() -> productStockService.recover(
                new ObjectOptimisticLockingFailureException("conflict", new RuntimeException()), productId, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_STOCK_CONFLICT);
    }
}
