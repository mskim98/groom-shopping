package groom.backend.application.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.application.coupon.event.CouponIssueRequestEvent;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.model.enums.CouponType;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.coupon.dto.request.CouponCreateRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;

/**
 * 재고 키 유실 폴백 부하 실측 - 초당 2,000 건 · 총 10,000 건 (초기화 잠금 유지)
 *
 * <p>접수 경로는 재고 키가 없으면 503 으로 돌려보내 Kafka 에 싣지 않는다. 그래서 판정 없이 실린 이벤트
 * ({@code gateReserved=false})를 요청 토픽에 직접 발행하고, 실제 리스너 컨테이너(컨슈머 4 개)가 처리한다
 *
 * <p>완료 시각은 클라이언트가 보는 상태 키를 10ms 주기로 조회해 잰다 - 주기만큼 크게 잡힐 수 있다
 *
 * <p>평소 테스트 실행에서는 돌지 않는다. 사용자 1 만 명을 만들고 수십 초가 걸린다
 * {@code MEASURE_FALLBACK_LOAD=1 ./gradlew test --rerun --tests '*CouponFallbackLoadMeasureTest'}
 *
 * <p>어노테이션을 {@link CouponAsyncIssueIntegrationTest} 와 똑같이 둔다. 달라지면 컨텍스트가 두 벌 떠
 * 컨슈머도 두 벌이 되고, 이 측정의 이벤트를 다른 컨텍스트의 컨슈머가 나눠 처리한다
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "MEASURE_FALLBACK_LOAD", matches = "1")
@DisplayName("쿠폰 폴백 부하 실측")
class CouponFallbackLoadMeasureTest {

    private static final int TOTAL = 10_000;
    private static final int RATE_PER_SEC = 2_000;
    // 이벤트 수의 절반 - 발급과 품절 두 갈래를 모두 지나야 초과 발급 여부가 드러난다
    private static final long QUANTITY = 5_000L;
    private static final String STATUS_KEY_PREFIX = "coupon:issue:status:";
    // 완료 시각 해상도. 이 값만큼 지연이 크게 잡힐 수 있다
    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(120);

    @Autowired
    private KafkaTemplate<String, String> couponIssueKafkaTemplate;
    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private CouponStockRedisRepository couponStockRedisRepository;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private final String runTag = UUID.randomUUID().toString().substring(0, 8);
    private Long couponId;
    private final List<String> requestIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (couponId != null) {
            redisTemplate.delete(List.of(CouponStockRedisRepository.stockKey(couponId),
                    CouponStockRedisRepository.issuedUsersKey(couponId)));
            jdbcTemplate.update("DELETE FROM coupon_issue WHERE coupon_id = ?", couponId);
            couponRepository.findById(couponId).ifPresent(couponRepository::delete);
        }
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "fbload-" + runTag + "-%");
        redisTemplate.delete(requestIds.stream().map(id -> STATUS_KEY_PREFIX + id).toList());
    }

    @Test
    @DisplayName("재고 키 유실 상태에서 초당 2,000건 · 총 1만 건이 들어와도 집계 1회 · 발급 수 = 발행 수량")
    void 재고키_유실_상태에서_초당_2000건_총_1만건() throws Exception {
        // given - 사용자 1 만 · 쿠폰 · 재고 키 없음
        List<Long> userIds = userRepository.saveAll(IntStream.range(0, TOTAL)
                        .mapToObj(i -> UserJpaEntity.builder()
                                .email("fbload-" + runTag + "-" + i + "@test.com").password("pw").name("폴백부하")
                                .role(Role.ROLE_USER).grade(Grade.BRONZE).build())
                        .toList())
                .stream().map(UserJpaEntity::getId).toList();
        Coupon coupon = couponRepository.save(CouponCreateRequest.builder()
                .name("폴백 부하 " + runTag).description("폴백 부하 실측").quantity(QUANTITY).amount(1000)
                .type(CouponType.DISCOUNT).expireDate(LocalDate.now().plusDays(30)).build()
                .toEntity());
        couponId = coupon.getId();
        // 생성 직후 커밋 훅이 재고를 심는 경로가 있다. 지워야 유실 상태가 된다
        redisTemplate.delete(List.of(CouponStockRedisRepository.stockKey(couponId),
                CouponStockRedisRepository.issuedUsersKey(couponId)));
        assertThat(couponStockRedisRepository.getStock(couponId)).isNull();

        // 발행 루프 안에서 직렬화하면 그 비용이 간격을 벌린다. 미리 만든다
        List<String> payloads = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            String requestId = UUID.randomUUID().toString();
            requestIds.add(requestId);
            payloads.add(objectMapper.writeValueAsString(
                    new CouponIssueRequestEvent(requestId, couponId, userIds.get(i), false)));
        }

        // 리스너가 파티션 4 개를 다 배정받기 전에 발행하면 배정 시점이 측정값에 섞인다
        MessageListenerContainer container = listenerRegistry.getAllListenerContainers().stream()
                .filter(c -> List.of(c.getContainerProperties().getTopics())
                        .contains(CouponAsyncIssueService.REQUEST_TOPIC))
                .findFirst().orElseThrow();
        ContainerTestUtils.waitForAssignment(container, 4);

        Logger logger = (Logger) LoggerFactory.getLogger(CouponAsyncIssueService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        long[] sentAt = new long[TOTAL];
        long[] doneAt = new long[TOTAL];
        String[] outcome = new String[TOTAL];
        AtomicInteger sendFailed = new AtomicInteger();
        AtomicInteger sentCount = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        double publishSec;
        long startEpochMs = System.currentTimeMillis();

        // 완료는 클라이언트가 보는 상태 키로 판정한다. 성공 결과는 결과 토픽에 싣지 않는 것이 기본값이다
        // (publish-success-results=false). 켜면 요청 토픽과 브로커를 두고 경합해 측정 대상이 달라진다
        // 발행됐고 아직 안 끝난 건만 조회하므로 한 번에 읽는 키는 처리 지연 × 2,000/s 정도다
        long deadline = startEpochMs + DRAIN_TIMEOUT.toMillis();
        Thread poller = new Thread(() -> {
            int from = 0;
            while (received.get() < TOTAL && System.currentTimeMillis() < deadline) {
                int upto = sentCount.get();
                List<Integer> pending = new ArrayList<>();
                for (int i = from; i < upto; i++) {
                    if (outcome[i] == null) {
                        pending.add(i);
                    }
                }
                if (!pending.isEmpty()) {
                    List<String> values = redisTemplate.opsForValue().multiGet(pending.stream()
                            .map(i -> STATUS_KEY_PREFIX + requestIds.get(i)).toList());
                    long now = System.currentTimeMillis();
                    for (int k = 0; k < pending.size(); k++) {
                        String v = values == null ? null : values.get(k);
                        if (v != null && !CouponAsyncIssueService.STATUS_WAITING.equals(v)) {
                            int i = pending.get(k);
                            outcome[i] = v;
                            doneAt[i] = now;
                            received.incrementAndGet();
                        }
                    }
                }
                while (from < upto && outcome[from] != null) {
                    from++;
                }
                LockSupport.parkNanos(POLL_INTERVAL.toNanos());
            }
        });

        try {
            poller.start();
            // when - 0.5ms 간격으로 1 만 건. 키는 접수 경로와 같은 userId
            long t0 = System.nanoTime();
            for (int i = 0; i < TOTAL; i++) {
                long wait = t0 + (long) i * 1_000_000_000L / RATE_PER_SEC - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
                sentAt[i] = System.currentTimeMillis();
                couponIssueKafkaTemplate.send(CouponAsyncIssueService.REQUEST_TOPIC,
                                userIds.get(i).toString(), payloads.get(i))
                        .whenComplete((sent, error) -> {
                            if (error != null) {
                                sendFailed.incrementAndGet();
                            }
                        });
                sentCount.set(i + 1);
            }
            publishSec = (System.nanoTime() - t0) / 1e9;
            couponIssueKafkaTemplate.flush();
            poller.join(DRAIN_TIMEOUT.toMillis());
        } finally {
            logger.detachAppender(appender);
        }

        long reinit = appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("[COUPON_STOCK_REINITIALIZED]")).count();
        Long issued = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupon_issue WHERE coupon_id = ?", Long.class, couponId);
        Long redisStock = couponStockRedisRepository.getStock(couponId);
        Map<String, Long> outcomes = new TreeMap<>();
        List<Long> latencies = new ArrayList<>();
        long lastDone = 0;
        for (int i = 0; i < TOTAL; i++) {
            outcomes.merge(outcome[i] == null ? "NO_STATUS" : outcome[i], 1L, Long::sum);
            if (outcome[i] != null) {
                latencies.add(doneAt[i] - sentAt[i]);
                lastDone = Math.max(lastDone, doneAt[i]);
            }
        }
        latencies.sort(null);
        double rate = TOTAL / publishSec;
        System.out.printf("[FALLBACK_LOAD] total=%d rate=%.0f/s publishSec=%.2f sendFailed=%d received=%d "
                        + "reinit=%d issued=%d redisStock=%s drainMs=%d "
                        + "latencyMs{p50=%d p95=%d p99=%d max=%d} outcomes=%s%n",
                TOTAL, rate, publishSec, sendFailed.get(), received.get(), reinit, issued, redisStock,
                lastDone - startEpochMs, pct(latencies, 50), pct(latencies, 95), pct(latencies, 99),
                pct(latencies, 100), outcomes);

        // then
        assertThat(sendFailed.get()).isZero();
        assertThat(rate).isBetween(RATE_PER_SEC * 0.95, RATE_PER_SEC * 1.05);
        assertThat(received.get()).isEqualTo(TOTAL);
        assertThat(reinit).isEqualTo(1);
        assertThat(issued).isEqualTo(QUANTITY);
        assertThat(redisStock).isZero();
        assertThat(outcomes).containsOnlyKeys("SUCCESS", "FAILED:COUPON_OUT_OF_STOCK");
        assertThat(outcomes.get("SUCCESS")).isEqualTo(QUANTITY);
    }

    // 최근접 순위 백분위. 100 이면 최댓값
    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
