package groom.backend.infrastructure.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.application.coupon.CouponStreamFixture;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * r120 쿠폰 접수 정체 재현 (진단용, 평소 실행에서는 건너뛴다)
 *
 * <p>실행: {@code COUPON_REPRO=1 COUPON_REPRO_OUT=<결과 파일> ./gradlew -p backend cleanTest test --tests '*CouponAcceptStallReproTest'}
 * (Redis 는 SPRING_DATA_REDIS_PORT 가 가리키는 컨테이너, 읽기용 명령은 COUPON_REPRO_CLI 로 바꾼다 - 기본 {@code docker exec r121-t3-redis redis-cli})
 *
 * <p>접수 Lua(운영과 같은 {@link CouponStockRedisRepository#enqueue})를 여러 스레드에서 목표 속도로 부르고, 같은 시각에 실제 {@link CouponStreamConsumer}
 * 루프를 돌리면서 별도 Lettuce 연결 하나가 10ms 마다 PING 을 보내 서버 쪽 정지와 공유 연결 쪽 정지를 가른다.
 * 장면마다 SLOWLOG · LATENCY · INFO commandstats · INFO persistence · CLIENT LIST 를 전후로 남긴다
 */
@SpringBootTest(properties = {"coupon.issue.stream.block-ms=1000"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "COUPON_REPRO", matches = "1")
@DisplayName("쿠폰 접수 정체 재현(진단)")
class CouponAcceptStallReproTest {

    private static final int THREADS = Integer.getInteger("coupon.repro.threads", 64);
    private static final int RATE = Integer.parseInt(System.getenv().getOrDefault("COUPON_REPRO_RATE", "500"));
    private static final int SECONDS = Integer.parseInt(System.getenv().getOrDefault("COUPON_REPRO_SECONDS", "6"));
    private static final String CLI = System.getenv().getOrDefault("COUPON_REPRO_CLI", "docker exec r121-t3-redis redis-cli");
    private static final Path OUT = Path.of(System.getenv().getOrDefault("COUPON_REPRO_OUT", "/dev/stdout"));

    @Autowired
    private CouponStreamConsumer consumer;
    @Autowired
    private CouponStockRedisRepository stockRepository;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;

    private CouponStreamFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CouponStreamFixture(couponRepository, couponIssueRepository, userRepository, redis);
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
        fixture.cleanup();
    }

    @Test
    @DisplayName("접수만")
    void 접수만_돌린다() throws Exception {
        runScene("A accept only", false, false);
    }

    @Test
    @DisplayName("접수 + 컨슈머 루프")
    void 접수와_컨슈머를_함께_돌린다() throws Exception {
        runScene("B accept + consumer", true, false);
    }

    @Test
    @DisplayName("접수 + 컨슈머 루프 + 상태 폴링")
    void 접수와_컨슈머와_상태폴링을_함께_돌린다() throws Exception {
        // r120 k6 가 하던 일 - 접수된 요청마다 100ms 간격으로 JWT 블랙리스트 EXISTS 와 상태 GET 을 SUCCESS 까지(최대 30초) 부른다
        runScene("C accept + consumer + status polling", true, true);
    }

    private void runScene(String label, boolean withConsumer, boolean withPolling) throws Exception {
        int requests = RATE * SECONDS;
        // r120 과 같은 모양 - 수량 1000, 요청 수는 속도 x 초, 사용자는 요청마다 하나
        Long couponId = fixture.coupon(1000);
        stockRepository.initStock(couponId, 1000L);
        List<Long> users = fixture.users(requests);
        cli("CONFIG", "SET", "slowlog-log-slower-than", "1000");
        cli("SLOWLOG", "RESET");
        cli("LATENCY", "RESET");
        cli("CONFIG", "RESETSTAT");
        emit("== " + label + " rate=" + RATE + " seconds=" + SECONDS + " threads=" + THREADS + " requests=" + requests);
        emit("-- before persistence\n" + grep(cli("INFO", "persistence"), "rdb_changes_since_last_save", "rdb_saves", "rdb_last_bgsave", "latest_fork_usec", "rdb_last_cow_size", "aof_enabled", "aof_delayed_fsync", "aof_last_write_status", "aof_rewrites"));
        emit("-- before save config: " + cli("CONFIG", "GET", "save").replace('\n', ' '));

        if (withConsumer) {
            consumer.start();
        }
        RedisClient probeClient = RedisClient.create(RedisURI.create(host, port));
        StatefulRedisConnection<String, String> probe = probeClient.connect();
        List<Long> probeMicros = Collections.synchronizedList(new ArrayList<>());
        AtomicLong probeErrors = new AtomicLong();
        Thread prober = Thread.ofPlatform().daemon(true).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                long t0 = System.nanoTime();
                try {
                    probe.sync().ping();
                    probeMicros.add((System.nanoTime() - t0) / 1000);
                } catch (RuntimeException e) {
                    probeErrors.incrementAndGet();
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        List<Long> latencyMicros = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger[] byResult = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        AtomicInteger errors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        ExecutorService pollers = Executors.newVirtualThreadPerTaskExecutor();
        AtomicLong polls = new AtomicLong();
        AtomicLong pollErrors = new AtomicLong();
        long start = System.nanoTime();
        long intervalNanos = 1_000_000_000L / RATE;
        for (int i = 0; i < requests; i++) {
            long due = start + i * intervalNanos;
            long wait = due - System.nanoTime();
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
            Long userId = users.get(i);
            String requestId = UUID.randomUUID().toString();
            pool.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    var result = stockRepository.enqueue(couponId, userId, requestId);
                    byResult[Math.min(result.ordinal(), 3)].incrementAndGet();
                    if (withPolling && result == CouponStockRedisRepository.IssueResult.SUCCESS) {
                        pollers.submit(() -> pollStatus(requestId, polls, pollErrors));
                    }
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
                // 요청을 보내기로 한 시각부터 잰다 - 풀이 밀려도 대기가 지연에 들어간다
                latencyMicros.add((System.nanoTime() - due) / 1000);
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        pollers.shutdown();
        pollers.awaitTermination(40, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        prober.interrupt();
        probe.close();
        probeClient.shutdown();
        // 컨슈머가 스트림을 비울 시간을 조금 준다
        if (withConsumer) {
            Thread.sleep(3000);
        }

        emit(String.format("accept: elapsed=%d ms p50=%d ms p95=%d ms p99=%d ms max=%d ms errors=%d results[SUCCESS,OUT_OF_STOCK,ALREADY,NOT_INIT]=%s",
                elapsedMs, pct(latencyMicros, 50), pct(latencyMicros, 95), pct(latencyMicros, 99), pct(latencyMicros, 100), errors.get(),
                Arrays.toString(Arrays.stream(byResult).mapToInt(AtomicInteger::get).toArray())));
        emit("status polls=" + polls.get() + " pollErrors=" + pollErrors.get());
        emit(String.format("probe(PING on its own connection, 10 ms interval): n=%d p50=%d ms p99=%d ms max=%d ms errors=%d",
                probeMicros.size(), pct(probeMicros, 50), pct(probeMicros, 99), pct(probeMicros, 100), probeErrors.get()));
        emit("-- after persistence\n" + grep(cli("INFO", "persistence"), "rdb_changes_since_last_save", "rdb_saves", "rdb_last_bgsave", "latest_fork_usec", "rdb_last_cow_size", "aof_enabled", "aof_delayed_fsync", "aof_last_write_status", "aof_rewrites"));
        emit("-- LATENCY LATEST\n" + cli("LATENCY", "LATEST"));
        emit("-- SLOWLOG LEN " + cli("SLOWLOG", "LEN").trim() + "\n" + cli("SLOWLOG", "GET", "10"));
        emit("-- commandstats\n" + grep(cli("INFO", "commandstats"), "cmdstat_evalsha", "cmdstat_exists", "cmdstat_get:", "cmdstat_eval:", "cmdstat_xreadgroup", "cmdstat_xack", "cmdstat_xdel", "cmdstat_ping", "cmdstat_xautoclaim"));
        emit("-- clients\n" + cli("CLIENT", "LIST").lines().map(l -> l.replaceAll(".*(id=\\d+).*(cmd=\\S+).*", "$1 $2")).toList());
        emit("-- stream length " + stockRepository.streamLength(couponId) + " issued rows " + couponIssueRepository.countByCoupon_Id(couponId));
    }

    private void pollStatus(String requestId, AtomicLong polls, AtomicLong pollErrors) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                redis.hasKey("blacklist:repro-" + requestId);
                String status = redis.opsForValue().get(CouponStockRedisRepository.statusKey(requestId));
                polls.incrementAndGet();
                if ("SUCCESS".equals(status)) {
                    return;
                }
            } catch (RuntimeException e) {
                pollErrors.incrementAndGet();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static long pct(List<Long> micros, int p) {
        List<Long> sorted;
        synchronized (micros) {
            sorted = new ArrayList<>(micros);
        }
        if (sorted.isEmpty()) {
            return -1;
        }
        Collections.sort(sorted);
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p / 100.0) - 1);
        return sorted.get(Math.max(idx, 0)) / 1000;
    }

    private static String grep(String text, String... prefixes) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            for (String prefix : prefixes) {
                if (line.startsWith(prefix)) {
                    sb.append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static void emit(String text) throws Exception {
        System.out.println("[COUPON_REPRO] " + text);
        if (!"/dev/stdout".equals(OUT.toString())) {
            Files.writeString(OUT, text + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static String cli(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(Arrays.asList(CLI.split(" ")));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor(30, TimeUnit.SECONDS);
            return sb.toString();
        }
    }
}
