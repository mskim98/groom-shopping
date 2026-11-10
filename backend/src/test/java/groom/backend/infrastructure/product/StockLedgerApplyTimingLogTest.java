package groom.backend.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import groom.backend.application.product.StockLedgerRepository;
import groom.backend.application.product.StockLedgerService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

// 적용 회차 시간 로그를 본다 - 스프링 컨텍스트 없이 원장 저장소와 서비스를 목으로 둔다
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("원장 적용 회차 시간 로그")
class StockLedgerApplyTimingLogTest {

    private static final String TAG = "[STOCK_LEDGER_APPLY]";

    private final StockLedgerRepository ledger = mock(StockLedgerRepository.class);
    private final StockLedgerService service = mock(StockLedgerService.class);
    private final StockLedgerApplyJob job = new StockLedgerApplyJob(ledger, service);

    @Test
    void apply_행을_적용한_회차_상품수와_행수와_걸린시간을_INFO_한줄로_남긴다(CapturedOutput output) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID idle = UUID.randomUUID();
        when(ledger.findProductIdsWithUnapplied()).thenReturn(List.of(first, second, idle));
        when(service.applyProduct(first)).thenReturn(3);
        when(service.applyProduct(second)).thenReturn(4);
        when(service.applyProduct(idle)).thenReturn(0);

        job.apply();

        List<String> lines = output.getAll().lines().filter(l -> l.contains(TAG)).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("INFO").containsPattern("products=2 rows=7 elapsedMs=\\d+");
    }

    @Test
    void apply_행을_적용하지_않은_회차_INFO_줄을_남기지_않는다(CapturedOutput output) {
        when(ledger.findProductIdsWithUnapplied()).thenReturn(List.of());

        job.apply();

        assertThat(output.getAll()).doesNotContain(TAG + " products=");
        assertThat(output.getAll().lines().filter(l -> l.contains("INFO") && l.contains(TAG))).isEmpty();
    }
}
