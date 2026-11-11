package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import groom.backend.infrastructure.coupon.CouponRequestTimingFilter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

// 쿠폰 접수 · 상태 조회의 단계 타이머와 느린 Redis 호출 WARN 한 줄(r121 Q-4)
// 느린 호출은 Redis 의 CLIENT PAUSE 로 만든다 - 일시정지 중에 들어온 명령이 그 시간만큼 기다린다
@SpringBootTest(properties = {"coupon.redis.slow-ms=50", "coupon.issue.stream.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 시험 컨텍스트는 메트릭 내보내기를 끈다 - 켜야 prometheus 레지스트리 빈이 생긴다
@AutoConfigureObservability
@DisplayName("쿠폰 Redis 지연 계측")
class CouponRedisLatencyInstrumentationTest {

    private static final Pattern SLOW_LINE = Pattern.compile("\\[COUPON_REDIS_SLOW\\] command=(.+?) elapsedMs=(\\d+) suppressed=(\\d+)");

    @Autowired
    private CouponAsyncIssueService service;
    @Autowired
    private CouponStockRedisRepository stockRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private PrometheusMeterRegistry prometheus;
    @Autowired
    private CouponRequestTimingFilter filter;
    @Autowired
    private CouponRedisLatency latency;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger slowLogger;
    private Long couponId;

    @BeforeEach
    void setUp() {
        couponId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        stockRepository.initStock(couponId, 1000L);
        latency.resetLogWindow();
        slowLogger = (Logger) LoggerFactory.getLogger(CouponRedisLatency.class);
        slowLogger.setLevel(Level.WARN);
        appender.start();
        slowLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        slowLogger.detachAppender(appender);
        appender.stop();
        redisTemplate.delete(List.of(CouponStockRedisRepository.stockKey(couponId),
                CouponStockRedisRepository.issuedUsersKey(couponId), CouponStockRedisRepository.streamKey(couponId)));
    }

    private void pauseRedis(long ms) {
        redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute("CLIENT", "PAUSE".getBytes(), String.valueOf(ms).getBytes(), "ALL".getBytes()));
    }

    private List<String> slowLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("[COUPON_REDIS_SLOW]")).toList();
    }

    @Test
    @DisplayName("임계값(50ms)보다 느린 접수 Redis 호출은 명령 · 경과 시간이 든 WARN 한 줄이다")
    void 접수_느린_호출은_한줄을_남긴다() {
        pauseRedis(150);

        service.enqueue(couponId, 1L);

        assertThat(slowLines()).hasSize(1);
        Matcher line = SLOW_LINE.matcher(slowLines().get(0));
        assertThat(line.matches()).isTrue();
        assertThat(line.group(1)).isEqualTo("EVALSHA coupon-enqueue");
        assertThat(Long.parseLong(line.group(2))).isGreaterThanOrEqualTo(50L);
        assertThat(line.group(3)).isEqualTo("0");
    }

    @Test
    @DisplayName("빠른 접수 · 상태 호출은 줄을 남기지 않는다")
    void 빠른_호출은_줄이_없다() {
        service.enqueue(couponId, 2L);
        service.getStatus(UUID.randomUUID().toString());

        assertThat(slowLines()).isEmpty();
    }

    @Test
    @DisplayName("느린 상태 조회는 GET coupon-status 로 한 줄을 남긴다")
    void 상태조회_느린_호출은_한줄을_남긴다() {
        pauseRedis(150);

        service.getStatus(UUID.randomUUID().toString());

        assertThat(slowLines()).hasSize(1);
        assertThat(slowLines().get(0)).contains("command=GET coupon-status");
    }

    @Test
    @DisplayName("한 초 안의 느린 호출 세 건은 한 줄이고, 다음 줄이 건너뛴 두 건을 센다")
    void 느린_호출_세건은_한줄이고_다음줄이_나머지를_센다() throws Exception {
        for (int i = 0; i < 3; i++) {
            pauseRedis(100);
            service.getStatus(UUID.randomUUID().toString());
        }
        assertThat(slowLines()).as("첫 초의 줄").hasSize(1);

        Thread.sleep(1100);
        pauseRedis(100);
        service.getStatus(UUID.randomUUID().toString());

        assertThat(slowLines()).hasSize(2);
        assertThat(slowLines().get(1)).contains("suppressed=2");
    }

    @Test
    @DisplayName("접수 · 상태 단계 타이머가 각 1회 이상 prometheus 에 나온다")
    void 단계_타이머가_prometheus에_나온다() throws Exception {
        service.enqueue(couponId, 3L);
        service.getStatus(UUID.randomUUID().toString());
        filter.doFilter(new MockHttpServletRequest("POST", "/v1/coupon/issue-async/" + couponId),
                new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/v1/coupon/issue-async/" + UUID.randomUUID() + "/status"),
                new MockHttpServletResponse(), new MockFilterChain());

        String scrape = prometheus.scrape();
        for (String stage : List.of("accept_request", "accept_lua", "status_request", "status_redis_read")) {
            Matcher count = Pattern.compile("coupon_stage_seconds_count\\{[^}]*stage=\"" + stage + "\"[^}]*\\} ([0-9.eE+]+)")
                    .matcher(scrape);
            assertThat(count.find()).as(stage + " 타이머").isTrue();
            assertThat(Double.parseDouble(count.group(1))).as(stage + " 횟수").isGreaterThanOrEqualTo(1.0);
        }
    }
}
