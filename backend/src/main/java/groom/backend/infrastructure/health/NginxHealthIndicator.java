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
public class NginxHealthIndicator implements HealthIndicator {

    @Value("${nginx.url:http://localhost:80}")
    private String nginxUrl;

    private final RestTemplate restTemplate;

    public NginxHealthIndicator(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public Health health() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    nginxUrl, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return Health.up()
                        .withDetail("status", "UP")
                        .withDetail("url", nginxUrl)
                        .withDetail("responseCode", response.getStatusCode().value())
                        .build();
            } else {
                return Health.down()
                        .withDetail("status", "DOWN")
                        .withDetail("responseCode", response.getStatusCode().value())
                        .build();
            }
        } catch (Exception e) {
            //log.error("Nginx health check failed", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("url", nginxUrl)
                    .build();
        }
    }
}
