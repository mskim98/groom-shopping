package groom.backend.infrastructure.health;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;


@Component
public class ZookeeperHealthIndicator implements HealthIndicator {
    @Value("${zookeeper.connect:localhost:2181}")
    private String zookeeperConnect;

    @Override
    public Health health() {
        try {
            String[] parts = zookeeperConnect.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                return Health.up()
                        .withDetail("status", "Reachable")
                        .withDetail("server", zookeeperConnect)
                        .build();
            }
        } catch (Exception e) {
            //log.error("Zookeeper health check failed", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("server", zookeeperConnect)
                    .build();
        }
    }
}
