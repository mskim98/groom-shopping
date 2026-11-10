package groom.backend.infrastructure.product;

import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.application.product.StockLedgerRepository;
import groom.backend.application.product.StockLedgerRepository.ProductRemaining;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// 부팅 시 플래시 세일 상품의 남은 재고(stock − 미적용 원장 합)를 Redis 로 복사한다(워밍업).
// 키가 이미 있으면 건드리지 않는다(SET NX) - 먼저 뜬 인스턴스가 쓴 키와 그 뒤 선점·복원이 반영된 값을 되돌리지 않기 위해서다
// DB 모드 상품은 건너뛴다 - 복귀(StockModeRecoveryJob)가 쓸 때까지 Redis 키는 쓰이지 않는다. 일반 상품은 Redis 를 쓰지 않는다
// 상품 수가 많아도 키셋(seek) 페이지네이션으로 일정 메모리만 쓴다
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWarmUpRunner implements ApplicationRunner {

    // 가장 작은 UUID(00000000-...) 보다 큰 id 부터 시작 → 사실상 전체를 id 오름차순으로 순회.
    private static final UUID MIN_UUID = new UUID(0L, 0L);

    private final StockLedgerRepository stockLedgerRepository;
    private final ProductStockRedisRepository productStockRedisRepository;

    // 한 번에 읽어올 페이지 크기(메모리/쿼리 횟수 트레이드오프). 필요 시 외부 설정으로 조정.
    @Value("${stock.warmup.batch-size:1000}")
    private int batchSize = 1000;

    @Override
    public void run(ApplicationArguments args) {
        int count = 0;
        UUID lastId = MIN_UUID;
        List<ProductRemaining> page;
        do {
            page = stockLedgerRepository.findFlashRemainingAfter(lastId, batchSize);
            for (ProductRemaining product : page) {
                if (productStockRedisRepository.initStockIfAbsent(product.id(), product.remaining())) {
                    count++;
                }
                lastId = product.id(); // 다음 페이지 시작점
            }
        } while (page.size() == batchSize); // 마지막(부족) 페이지면 종료

        log.info("[STOCK_WARMUP] 플래시 상품 Redis 재고 워밍업 완료 - {}건 (batchSize={})", count, batchSize);
    }
}
