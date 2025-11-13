package groom.backend.infrastructure.health;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Component
public class FrontendUrlHealthIndicator implements HealthIndicator {

    private final RestTemplate rest;
    private final String url;

    public FrontendUrlHealthIndicator(RestTemplateBuilder builder,
                                      @Value("${frontend.url:http://localhost:3000}") String url) {
        this.rest = builder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(3))
                .build();
        this.url = url;
    }

    @Override
    public Health health() {
        try {
            ResponseEntity<String> resp = rest.getForEntity(url + "/health", String.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                return Health.up().withDetail("status", resp.getStatusCodeValue()).build();
            } else {
                return Health.down().withDetail("status", resp.getStatusCodeValue()).build();
            }
        } catch (Exception e) {
            return Health.down(e).withDetail("url", url).build();
        }
    }
}
