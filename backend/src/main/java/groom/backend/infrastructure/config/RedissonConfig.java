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
    // 워치독 갱신 주기는 이 값의 1/3 이다(15초 → 5초마다 갱신).
    // leaseTime 을 생략한 tryLock 은 락 TTL 을 이 값으로 걸고 보유 중에는 계속 갱신하므로,
    // 임계 구역이 예상보다 길어져도 작업 도중 락이 풀리지 않는다.
    // 기본값 30초에서 줄인 이유 : 프로세스가 죽으면 갱신 스레드도 함께 죽어 TTL 만료로 회수되는데,
    // 그 회수 지연이 곧 해당 쿠폰의 발급 정지 시간이다.
    private static final long LOCK_WATCHDOG_TIMEOUT_MILLIS = 15_000L;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setLockWatchdogTimeout(LOCK_WATCHDOG_TIMEOUT_MILLIS);
        // 단일 Redis 서버 모드. 커넥션 풀을 미리 확보해 락 경합 시에도 연결 지연을 줄인다.
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8);
        return Redisson.create(config);
    }
}
