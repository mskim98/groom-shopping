package groom.backend.infrastructure.health;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class KafkaHealthIndicator implements HealthIndicator {
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Override
    public Health health() {
        try {
            Map<String, Object> configs = new HashMap<>();
            configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            configs.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);

            try (AdminClient adminClient = AdminClient.create(configs)) {
                DescribeClusterResult clusterResult = adminClient.describeCluster();

                String clusterId = clusterResult.clusterId().get(3, TimeUnit.SECONDS);
                Collection<Node> nodes = clusterResult.nodes().get(3, TimeUnit.SECONDS);

                return Health.up()
                        .withDetail("clusterId", clusterId)
                        .withDetail("nodeCount", nodes.size())
                        .withDetail("bootstrapServers", bootstrapServers)
                        .build();
            }
        } catch (Exception e) {
            log.error("Kafka health check failed", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();
        }
    }

}
