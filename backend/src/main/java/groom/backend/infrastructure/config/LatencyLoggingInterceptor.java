package groom.backend.infrastructure.config;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

// ClientHttpRequestInterceptor : RestTemplate 의 모든 외부 호출을 가로채(intercept) 전/후 작업을 끼워 넣는다.
// 여기서는 호출 직전·직후 시각을 재서 외부 API 응답 시간(latency)을 구조화 로그로 남긴다.
@Slf4j
public class LatencyLoggingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        // 실제 호출 직전 시각 기록 (nanoTime : 경과 시간 측정 전용, 벽시계가 아니라 단조 증가)
        long start = System.nanoTime();
        try {
            // execution.execute(...) : 다음 인터셉터(또는 실제 네트워크 호출)로 넘긴다.
            return execution.execute(request, body);
        } finally {
            // 성공/실패와 무관하게 응답 시간을 남기기 위해 finally 에서 기록한다.
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[TOSS_API_LATENCY] {} {} - {}ms", request.getMethod(), request.getURI(), elapsedMs);
        }
    }
}
