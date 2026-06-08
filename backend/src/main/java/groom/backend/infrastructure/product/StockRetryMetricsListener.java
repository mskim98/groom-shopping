package groom.backend.infrastructure.product;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

/**
 * 재고 낙관적 락(@Retryable) 재시도 횟수를 Micrometer 로 노출하는 리스너 (정량 실측용).
 *
 * <p>Spring Retry 는 기본적으로 Micrometer 지표를 노출하지 않으므로, {@code @Retryable(listeners=...)} 로
 * 이 리스너를 부착해 호출당 재시도 횟수를 분포(summary)로 집계한다.
 * Prometheus 에서 평균 재시도 = {@code stock_optimistic_retries_sum / stock_optimistic_retries_count} (op 태그별).
 *
 * <p>{@link RetryContext#getRetryCount()} = (재시도=실패) 횟수. 첫 시도에 성공하면 0.
 */
@Component("stockRetryListener")
@RequiredArgsConstructor
public class StockRetryMetricsListener implements RetryListener {

    private static final String SUMMARY_NAME = "stock_optimistic_retries";

    private final MeterRegistry meterRegistry;

    @Override
    public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        // 차감/증가 경로를 op 태그로 구분(@Retryable 메서드 라벨 기반).
        String label = String.valueOf(context.getAttribute(RetryContext.NAME));
        String op = label.contains("increase") ? "increase" : "decrease";
        // 호출당 재시도 횟수를 기록 → 평균/분포 산출 가능.
        meterRegistry.summary(SUMMARY_NAME, "op", op).record(context.getRetryCount());
    }
}
