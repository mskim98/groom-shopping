package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

// 토큰 저장소(리프레시 토큰 · 블랙리스트)가 주 Redis 와 따로 연결을 가진다
// 주 Redis 가 멈춰도 인증 필터의 블랙리스트 조회가 살아 있어야 쿠폰 발급이 DB 대체 경로까지 간다
// Lettuce 는 첫 명령 때 연결하므로 Redis 없이 빈이 읽는 호스트 · 포트 · 옵션만 본다
@DisplayName("토큰 저장소 Redis 연결 설정")
class TokenRedisConnectionConfigTest {

    private ClientResources resources;

    @BeforeEach
    void setUp() {
        resources = DefaultClientResources.create();
    }

    @AfterEach
    void tearDown() {
        resources.shutdown();
    }

    private RedisConfig config(String tokenHost, int tokenPort) {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "host", "main-redis");
        ReflectionTestUtils.setField(config, "port", 6379);
        ReflectionTestUtils.setField(config, "tokenHost", tokenHost);
        ReflectionTestUtils.setField(config, "tokenPort", tokenPort);
        ReflectionTestUtils.setField(config, "commandTimeout", Duration.ofMillis(700));
        return config;
    }

    private LettuceConnectionFactory lettuce(Object factory) {
        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
        return (LettuceConnectionFactory) factory;
    }

    @Test
    @DisplayName("token.redis.host · port 를 주면 토큰 연결은 그 주소로, 주 연결은 spring.data.redis 주소로 간다")
    void 토큰연결은_token_redis_주소로_주연결은_주_Redis_주소로_간다() {
        RedisConfig config = config("redis-token", 6380);

        LettuceConnectionFactory token = lettuce(config.tokenRedisConnectionFactory(resources));
        LettuceConnectionFactory main = lettuce(config.redisConnectionFactory(resources));

        assertThat(token.getHostName()).isEqualTo("redis-token");
        assertThat(token.getPort()).isEqualTo(6380);
        assertThat(main.getHostName()).isEqualTo("main-redis");
        assertThat(main.getPort()).isEqualTo(6379);
    }

    @Test
    @DisplayName("토큰 연결의 명령 타임아웃과 끊긴 동안 명령 거절 옵션은 주 연결과 같다")
    void 토큰연결은_주연결과_같은_타임아웃과_거절옵션을_가진다() {
        RedisConfig config = config("redis-token", 6380);

        LettuceConnectionFactory token = lettuce(config.tokenRedisConnectionFactory(resources));

        assertThat(token.getTimeout()).isEqualTo(700L);
        assertThat(token.getClientConfiguration().getClientResources()).containsSame(resources);
        assertThat(token.getClientConfiguration().getClientOptions()).get()
                .extracting(ClientOptions::getDisconnectedBehavior)
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    @Test
    @DisplayName("토큰 템플릿 둘(리프레시 토큰용 · 문자열용)이 토큰 연결 팩토리에 붙는다")
    void 토큰_템플릿들은_토큰연결에_붙고_주연결에_붙지_않는다() {
        RedisConfig config = config("redis-token", 6380);
        LettuceConnectionFactory token = lettuce(config.tokenRedisConnectionFactory(resources));
        LettuceConnectionFactory main = lettuce(config.redisConnectionFactory(resources));

        RedisTemplate<?, ?> refresh = config.tokenRedisTemplate(token);
        RedisTemplate<?, ?> string = config.tokenStringRedisTemplate(token);
        RedisTemplate<?, ?> mainString = config.redisTemplate(main);

        assertThat(refresh.getConnectionFactory()).isSameAs(token);
        assertThat(string.getConnectionFactory()).isSameAs(token);
        assertThat(mainString.getConnectionFactory()).isSameAs(main).isNotSameAs(token);
    }
}
