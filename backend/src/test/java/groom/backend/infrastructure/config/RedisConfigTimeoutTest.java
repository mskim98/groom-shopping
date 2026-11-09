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
import org.springframework.test.context.TestPropertySource;

/**
 * Redis 명령 타임아웃이 적용된다
 *
 * <p>{@code new LettuceConnectionFactory(host, port)} 는 {@code spring.data.redis.timeout} 을 읽지 않아
 * 명령 타임아웃이 기본 60초다. Redis 가 응답을 멈추면 접수 스레드가 60초씩 붙들리고,
 * Boot 가 만든 {@code ClientResources} 를 버려 Lettuce 명령 지연 지표도 사라진다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 기본값(1초)이 아니라 설정값이 적용되는지 보려고 다른 값을 준다. 전용 컨텍스트라 Streams 락이 부딪히지 않게 끈다
@TestPropertySource(properties = {"spring.data.redis.timeout=700ms", "spring.kafka.streams.auto-startup=false"})
@DisplayName("Redis 연결 팩토리 타임아웃")
class RedisConfigTimeoutTest {

    @Autowired
    private RedisConnectionFactory connectionFactory;
    @Autowired
    private ClientResources bootClientResources;

    private LettuceConnectionFactory lettuce() {
        assertThat(connectionFactory).isInstanceOf(LettuceConnectionFactory.class);
        return (LettuceConnectionFactory) connectionFactory;
    }

    @Test
    @DisplayName("명령 타임아웃이 spring.data.redis.timeout 설정값을 따른다")
    void connectionFactory_명령_타임아웃이_설정값이다() {
        assertThat(lettuce().getTimeout()).isEqualTo(700L);
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
