package groom.backend.infrastructure.config;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    // TCP 핸드셰이크 한도 (연결 수립까지 걸리는 시간)
    @Value("${http.client.toss.connect-timeout:3s}")
    private Duration connectTimeout;

    // 응답 수신 한도 (요청 보낸 뒤 응답이 올 때까지 기다리는 시간)
    @Value("${http.client.toss.response-timeout:5s}")
    private Duration responseTimeout;

    // 취소 전용 응답 수신 한도. 토스가 장애 시 30초를 권장한다 - 5초로 자르면 정상 처리 중인 취소를
    // 실패로 오판해 보상 장부에 채무가 남는다. 승인은 사용자가 기다리는 요청이라 5초를 유지한다
    @Value("${http.client.toss.cancel-response-timeout:30s}")
    private Duration cancelResponseTimeout;

    // 풀에서 커넥션을 빌릴 때 대기 한도 (풀 고갈 시 무한 대기 방지)
    @Value("${http.client.toss.connection-request-timeout:2s}")
    private Duration connectionRequestTimeout;

    // 풀 전체 최대 커넥션 수
    @Value("${http.client.toss.max-total:50}")
    private int maxTotal;

    // 같은 목적지(route)당 최대 커넥션 수 (PG 호출이 한곳으로 몰리므로 per-route 가 중요)
    @Value("${http.client.toss.max-per-route:20}")
    private int maxPerRoute;

    // @Bean : 풀링 + 타임아웃이 적용된 RestTemplate 을 빈으로 등록한다.
    // 기존 'return new RestTemplate();' 은 JDK 기본 클라이언트라 무한 대기 가능 → Apache HttpClient5 로 교체.
    // @Primary : 취소 전용 템플릿이 추가되면서 같은 타입 빈이 둘이 됐다. 한정자 없는 주입은 이쪽(승인·5s)
    @Bean
    @Primary
    public RestTemplate restTemplate(LatencyLoggingInterceptor latencyLoggingInterceptor) {
        return build(responseTimeout, latencyLoggingInterceptor);
    }

    // 취소 전용. 타임아웃만 다른 게 아니라 커넥션 풀도 따로 둔다 -
    // 30초를 기다리는 취소가 승인 풀을 점유하면 사용자 결제가 풀 획득 대기(2s)에서 죽는다
    @Bean
    public RestTemplate tossCancelRestTemplate(LatencyLoggingInterceptor latencyLoggingInterceptor) {
        return build(cancelResponseTimeout, latencyLoggingInterceptor);
    }

    private RestTemplate build(Duration responseTimeout, LatencyLoggingInterceptor latencyLoggingInterceptor) {
        // ConnectionConfig : 커넥션 자체에 거는 타임아웃 (HttpClient5 에서 connect/socket 은 여기로 분리됨)
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                .build();

        // PoolingHttpClientConnectionManager : 커넥션을 재사용해 매 요청 TCP 핸드셰이크 비용을 없앤다.
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        // RequestConfig : 요청 단위 타임아웃 (풀 획득 대기 + 응답 수신 한도)
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeout.toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // 만료/유휴 커넥션을 주기적으로 정리해 죽은 커넥션 재사용을 막는다.
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        RestTemplate template = new RestTemplate(factory);
        // 외부 호출 응답 시간을 로그로 남기는 인터셉터 등록
        template.getInterceptors().add(latencyLoggingInterceptor);
        return template;
    }

    // @Bean : 인터셉터를 빈으로 등록해 위 restTemplate() 에 주입한다.
    @Bean
    public LatencyLoggingInterceptor latencyLoggingInterceptor() {
        return new LatencyLoggingInterceptor();
    }
}
