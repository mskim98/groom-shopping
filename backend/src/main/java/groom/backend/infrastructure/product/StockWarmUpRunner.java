package groom.backend.infrastructure.product;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

// 부팅 시 DB 의 상품 재고를 Redis 로 복사한다(워밍업).
// DB 가 원천이므로 부팅 시점 Redis=DB 로 맞춰두면, 이후 선점/복원이 같은 기준에서 동작한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWarmUpRunner implements ApplicationRunner {

    private final SpringDataProductRepository productRepository;
    private final ProductStockRedisRepository productStockRedisRepository;

    @Override
    public void run(ApplicationArguments args) {
        int count = 0;
        for (ProductJpaEntity product : productRepository.findAll()) {
            if (product.getStock() != null) {
                productStockRedisRepository.initStock(product.getId(), product.getStock());
                count++;
            }
        }
        log.info("[STOCK_WARMUP] Redis 재고 워밍업 완료 - {}건", count);
    }
}
