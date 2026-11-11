package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.coupon.CouponRequestTimingFilter;
import groom.backend.infrastructure.coupon.CouponStreamConsumer;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

// 쿠폰 접수 · 상태 조회의 단계 타이머와 느린 Redis 호출 WARN 한 줄(r121 Q-4)
// 느린 호출은 Redis 의 CLIENT PAUSE 로 만든다 - 일시정지 중에 들어온 명령이 그 시간만큼 기다린다
// 컨슈머 시험은 순회를 손으로 돌린다 - 읽기 대기(block-ms)를 100ms 로 줄이고 활성 쿠폰 목록을 순회마다 다시 읽게 한다(방금 만든 쿠폰이 바로 감시 대상이 된다)
// 유휴 스캔 간격(시험 기본 100ms)을 늘려 쿠폰마다 첫 순회에만 인수가 돌게 한다 - 읽기를 느리게 만드는 시험에서 인수가 일시정지를 먼저 받지 않는다
@SpringBootTest(properties = {"coupon.redis.slow-ms=50", "coupon.issue.stream.enabled=false",
        "coupon.issue.stream.block-ms=100", "coupon.issue.stream.refresh-ms=0", "coupon.issue.stream.claim-interval-ms=60000"})
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
    @Autowired
    private CouponStreamConsumer consumer;
    @Autowired
    private StringRedisTemplate stringRedis;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;

    private CouponStreamFixture fixture;

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
        if (fixture != null) {
            fixture.cleanup();
        }
        slowLogger.detachAppender(appender);
        appender.stop();
        redisTemplate.delete(List.of(CouponStockRedisRepository.stockKey(couponId),
                CouponStockRedisRepository.issuedUsersKey(couponId), CouponStockRedisRepository.streamKey(couponId)));
    }

    private void pauseRedis(long ms) {
        pauseRedis(ms, "ALL");
    }

    // WRITE 는 쓰기 명령(XAUTOCLAIM · XREADGROUP · XACK · SET)만 멈추고 XINFO 같은 읽기는 통과시킨다 - 컨슈머의 어느 호출이 느려지는지 고른다
    private void pauseRedis(long ms, String mode) {
        redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute("CLIENT", "PAUSE".getBytes(), String.valueOf(ms).getBytes(), mode.getBytes()));
    }

    // 앞 시험이 남긴 활성 쿠폰의 그룹 생성 · 유휴 스캔을 미리 끝내 측정할 순회에 끼어들지 않게 하고, 이 시험의 쿠폰은 그룹까지만 만들어 둔다
    private Long consumerCoupon() {
        consumer.pollOnce();
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, stringRedis);
        Long id = fixture.coupon(10);
        stockRepository.initStock(id, 10L);
        consumer.ensureGroup(id);
        return id;
    }

    // 측정할 호출 직전에 부른다 - 앞선 준비 순회가 남긴 줄과 1초 간격 기록을 지운다
    private void resetLog() {
        latency.resetLogWindow();
        appender.list.clear();
    }

    private String redisConfig(String name) {
        return redisTemplate.execute((RedisCallback<String>) connection ->
                connection.serverCommands().getConfig(name).getProperty(name));
    }

    private void setRedisConfig(String name, String value) {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().setConfig(name, value);
            return null;
        });
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

    @Test
    @DisplayName("컨슈머의 유휴 항목 인수(XAUTOCLAIM)가 느리면 그 명령 이름으로 한 줄을 남긴다")
    void 컨슈머_인수가_느리면_XAUTOCLAIM_줄을_남긴다() {
        consumerCoupon();
        resetLog();
        pauseRedis(300, "WRITE");

        consumer.pollOnce();

        assertThat(slowLines()).hasSize(1);
        assertThat(slowLines().get(0)).contains("command=XAUTOCLAIM coupon-issue");
    }

    @Test
    @DisplayName("컨슈머의 읽기(XREADGROUP)가 대기 시간을 빼고도 느리면 그 명령 이름으로 한 줄을 남긴다")
    void 컨슈머_읽기가_느리면_XREADGROUP_줄을_남긴다() {
        Long id = consumerCoupon();
        consumer.pollOnce();
        stockRepository.enqueue(id, fixture.user(), fixture.requestId());
        resetLog();
        pauseRedis(300, "WRITE");

        consumer.pollOnce();

        assertThat(slowLines()).hasSize(1);
        assertThat(slowLines().get(0)).contains("command=XREADGROUP coupon-issue");
    }

    @Test
    @DisplayName("항목이 없어 읽기가 대기 시간(100ms)을 다 기다려도 줄을 남기지 않는다")
    void 컨슈머_읽기가_대기시간만큼_기다려도_줄이_없다() {
        consumerCoupon();
        consumer.pollOnce();
        // Redis 는 대기 중인 읽기의 시간 만료를 1000/hz ms 간격으로 처리한다(기본 hz 10 = 최대 100ms 늦게 돌아온다) - 시험의 임계값 50ms 보다 커서 hz 를 올린다
        String hz = redisConfig("hz");
        setRedisConfig("hz", "100");
        long elapsedMs;
        try {
            resetLog();
            long started = System.nanoTime();
            consumer.pollOnce();
            elapsedMs = (System.nanoTime() - started) / 1_000_000;
        } finally {
            setRedisConfig("hz", hz);
        }

        // 읽기가 실제로 대기 시간을 기다렸어야 이 시험이 허용치를 시험한 것이다(다른 쿠폰 스트림에 항목이 있어 바로 돌아오면 여기서 실패한다)
        assertThat(elapsedMs).as("순회 시간").isGreaterThanOrEqualTo(90L);
        assertThat(slowLines()).isEmpty();
    }
}
