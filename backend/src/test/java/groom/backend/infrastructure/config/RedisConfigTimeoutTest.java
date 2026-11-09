package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.resource.ClientResources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;

/**
 * Redis 명령 타임아웃이 적용된다
 *
 * <p>{@code new LettuceConnectionFactory(host, port)} 는 {@code spring.data.redis.timeout} 을 읽지 않아
 * 명령 타임아웃이 기본 60초다. Redis 가 응답을 멈추면 접수 스레드가 60초씩 붙들리고,
 * Boot 가 만든 {@code ClientResources} 를 버려 Lettuce 명령 지연 지표도 사라진다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Redis 연결 팩토리 타임아웃")
class RedisConfigTimeoutTest {

    @Autowired
    private RedisConnectionFactory connectionFactory;
    @Autowired
    private ClientResources bootClientResources;
    @Autowired
    private Environment environment;

    private LettuceConnectionFactory lettuce() {
        assertThat(connectionFactory).isInstanceOf(LettuceConnectionFactory.class);
        return (LettuceConnectionFactory) connectionFactory;
    }

    // 이 클래스는 전용 컨텍스트를 만들지 않는다. 캐시된 컨텍스트마다 Hikari 풀이 PostgreSQL 연결 상한을 갉아 먹는다
    @Test
    @DisplayName("기동한 앱의 명령 타임아웃이 설정값(없으면 1초)이고 1초를 넘지 않는다")
    void connectionFactory_명령_타임아웃이_설정값이다() {
        long configured = environment.getProperty("spring.data.redis.timeout", Duration.class, Duration.ofSeconds(1))
                .toMillis();

        assertThat(lettuce().getTimeout()).isEqualTo(configured).isLessThanOrEqualTo(1_000L);
    }

    @Test
    @DisplayName("설정에 준 값이 그대로 명령 타임아웃이 된다")
    void redisConnectionFactory_설정값이_명령_타임아웃이_된다() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "host", "localhost");
        ReflectionTestUtils.setField(config, "port", 6379);
        ReflectionTestUtils.setField(config, "commandTimeout", Duration.ofMillis(700));
        ClientResources resources = DefaultClientResources.create();
        try {
            LettuceConnectionFactory factory = (LettuceConnectionFactory) config.redisConnectionFactory(resources);

            assertThat(factory.getTimeout()).isEqualTo(700L);
            assertThat(factory.getClientConfiguration().getClientResources()).containsSame(resources);
        } finally {
            resources.shutdown();
        }
    }

    @Test
    @DisplayName("Boot 의 ClientResources 를 그대로 써서 Lettuce 명령 지연 지표가 유지된다")
    void connectionFactory_Boot_ClientResources_를_공유한다() {
        assertThat(lettuce().getClientConfiguration().getClientResources())
                .containsSame(bootClientResources);
        assertThat(bootClientResources.commandLatencyRecorder())
                .isInstanceOf(MicrometerCommandLatencyRecorder.class);
    }

    @Test
    @DisplayName("연결이 끊긴 동안 명령은 큐에 쌓지 않고 즉시 거절한다")
    void connectionFactory_끊긴_동안_명령을_거절한다() {
        assertThat(lettuce().getClientConfiguration().getClientOptions())
                .get()
                .extracting(ClientOptions::getDisconnectedBehavior)
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
