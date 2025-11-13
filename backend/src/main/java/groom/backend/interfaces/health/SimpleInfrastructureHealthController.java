package groom.backend.interfaces.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/actuator/custom-health")
@Slf4j
public class SimpleInfrastructureHealthController {

    private final HealthContributorRegistry healthRegistry;

    public SimpleInfrastructureHealthController(HealthContributorRegistry healthRegistry) {
        this.healthRegistry = healthRegistry;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllHealth() {
        Map<String, Object> components = new HashMap<>();

        healthRegistry.forEach(entry -> {
            String name = entry.getName();
            HealthContributor contributor = entry.getContributor();

            if (contributor instanceof HealthIndicator) {
                Health health = ((HealthIndicator) contributor).health();
                components.put(name, Map.of(
                        "status", health.getStatus().getCode(),
                        "details", health.getDetails()
                ));
            }
        });

        boolean allUp = components.values().stream()
                .allMatch(c -> "UP".equals(((Map<String, Object>) c).get("status")));

        Map<String, Object> response = new HashMap<>();
        response.put("status", allUp ? "UP" : "DOWN");
        response.put("timestamp", LocalDateTime.now());
        response.put("components", components);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getHealthSummary() {
        Map<String, String> summary = new HashMap<>();

        healthRegistry.forEach(entry -> {
            if (entry.getContributor() instanceof HealthIndicator) {
                Health health = ((HealthIndicator) entry.getContributor()).health();
                summary.put(entry.getName(), health.getStatus().getCode());
            }
        });

        boolean allUp = summary.values().stream().allMatch("UP"::equals);

        return ResponseEntity.ok(Map.of(
                "overallStatus", allUp ? "UP" : "DOWN",
                "summary", summary,
                "timestamp", LocalDateTime.now()
        ));
    }

    @GetMapping("/critical")
    public ResponseEntity<Map<String, Object>> getCriticalComponents() {
        List<String> criticalComponents = Arrays.asList("db", "redis", "kafka");
        Map<String, Object> critical = new HashMap<>();

        healthRegistry.forEach(entry -> {
            if (criticalComponents.contains(entry.getName()) &&
                    entry.getContributor() instanceof HealthIndicator) {
                Health health = ((HealthIndicator) entry.getContributor()).health();
                critical.put(entry.getName(), Map.of(
                        "status", health.getStatus().getCode(),
                        "details", health.getDetails()
                ));
            }
        });

        boolean allCriticalUp = critical.values().stream()
                .allMatch(c -> "UP".equals(((Map<String, Object>) c).get("status")));

        return ResponseEntity.ok(Map.of(
                "criticalStatus", allCriticalUp ? "UP" : "DOWN",
                "components", critical,
                "timestamp", LocalDateTime.now()
        ));
    }
}