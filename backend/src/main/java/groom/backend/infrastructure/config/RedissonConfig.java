package groom.backend.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 설정 - 분산 락 및 Lua 스크립트 실행용
 */
// @Configuration : 스프링 빈을 정의하는 설정 클래스임을 나타낸다.
@Configuration
public class RedissonConfig {

    // @Value : application.yml 의 설정 값을 필드에 주입한다(서버 환경마다 다른 값 사용 가능).
    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    // @Bean : 이 메서드가 반환하는 객체를 스프링 컨테이너가 관리하는 빈으로 등록한다.
    //         이렇게 등록해 두면 다른 곳에서 RedissonClient 를 주입받아 분산 락에 쓸 수 있다.
    // destroyMethod = "shutdown" : 앱이 종료될 때 shutdown()을 호출해 Redis 연결을 깔끔히 닫는다.
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 단일 Redis 서버 모드. 커넥션 풀을 미리 확보해 락 경합 시에도 연결 지연을 줄인다.
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8);
        return Redisson.create(config);
    }
}
