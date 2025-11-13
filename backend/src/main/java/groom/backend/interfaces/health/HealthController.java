package groom.backend.interfaces.health;

import com.sun.management.OperatingSystemMXBean;
import groom.backend.application.health.HealthCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
public class HealthController {
    private final HealthCheckService service;

    @GetMapping("/health")
    public ResponseEntity<ServerHealth> health() {
        return ResponseEntity.ok(
                new ServerHealth(
                        service.isConnected(),
                        new File("/"),
                        Runtime.getRuntime(),
                        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()
                )
        );
    }
}
