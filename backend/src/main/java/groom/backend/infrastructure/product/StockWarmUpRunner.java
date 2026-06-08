package groom.backend.infrastructure.product;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

// 부팅 시 DB 의 상품 재고를 Redis 로 복사한다(워밍업).
// DB 가 원천이므로 부팅 시점 Redis=DB 로 맞춰두면, 이후 선점/복원이 같은 기준에서 동작한다.
//
// 과거에는 productRepository.findAll() 로 전량을 한 번에 영속성 컨텍스트에 적재했는데,
// 상품 수가 매우 많으면(부하테스트 시드 등) 부팅 시 OutOfMemoryError 위험이 있었다.
// → 키셋(seek) 페이지네이션으로 일정 메모리만 쓰며 순회하도록 변경한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWarmUpRunner implements ApplicationRunner {

    // 가장 작은 UUID(00000000-...) 보다 큰 id 부터 시작 → 사실상 전체를 id 오름차순으로 순회.
    private static final UUID MIN_UUID = new UUID(0L, 0L);

    private final SpringDataProductRepository productRepository;
    private final ProductStockRedisRepository productStockRedisRepository;

    // 한 번에 읽어올 페이지 크기(메모리/쿼리 횟수 트레이드오프). 필요 시 외부 설정으로 조정.
    @Value("${stock.warmup.batch-size:1000}")
    private int batchSize;

    @Override
    public void run(ApplicationArguments args) {
        int count = 0;
        UUID lastId = MIN_UUID;
        List<ProductJpaEntity> page;
        do {
            page = productRepository.findByIdGreaterThanOrderByIdAsc(lastId, PageRequest.of(0, batchSize));
            for (ProductJpaEntity product : page) {
                if (product.getStock() != null) {
                    productStockRedisRepository.initStock(product.getId(), product.getStock());
                    count++;
                }
                lastId = product.getId(); // 다음 페이지 시작점(재고 null 이어도 커서는 전진)
            }
        } while (page.size() == batchSize); // 마지막(부족) 페이지면 종료

        log.info("[STOCK_WARMUP] Redis 재고 워밍업 완료 - {}건 (batchSize={})", count, batchSize);
    }
}
