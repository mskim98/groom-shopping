package groom.backend.infrastructure.coupon;

import groom.backend.application.coupon.CouponIssueBatchWriter;
import groom.backend.application.coupon.CouponIssueBatchWriter.InsertOutcome;
import groom.backend.application.coupon.CouponIssueStatus;
import groom.backend.application.coupon.CouponStockRedisRepository;
import groom.backend.common.exception.ErrorCode;
import groom.backend.domain.coupon.model.entity.Coupon;
import groom.backend.domain.coupon.repository.CouponIssueRepository;
import groom.backend.domain.coupon.repository.CouponRepository;
import groom.backend.infrastructure.config.RedisConfig;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 요청 스트림 컨슈머 - 인스턴스마다 전용 스레드 하나가 도는 루프
 *
 * <p>흐름
 * <ol>
 *   <li>활성 쿠폰의 스트림 {@code coupon:{id}:req} 를 컨슈머 그룹 {@code coupon-issue} 으로 읽는다
 *       ({@code XREADGROUP COUNT batch-size BLOCK block-ms}). 그룹이 없으면 {@code XGROUP CREATE … MKSTREAM} 으로 만든다</li>
 *   <li>쿠폰마다 한 트랜잭션: 쿠폰 행 잠금 → 남은 자리({@code quantity − 발급 수}) → 그 수만큼 배치 INSERT → 커밋
 *       ({@link CouponIssueBatchWriter})</li>
 *   <li><b>커밋 뒤에</b> 상태(SUCCESS · 자리 없음 FAILED) · 내 쿠폰 캐시 비움 · {@code XACK}(+ 스트림 항목 삭제).
 *       커밋과 ack 사이에 죽으면 항목이 재전달되고 {@code ON CONFLICT} 와 request_id 비교가 흡수한다</li>
 *   <li>다른 컨슈머가 {@code claim-min-idle-ms} 이상 쥔 항목은 가져와(XAUTOCLAIM) 같은 방식으로 처리한다</li>
 *   <li>배달 횟수가 {@code max-deliveries} 를 넘은 항목은 {@code coupon:{id}:dlq} 로 옮기고 ack 하며
 *       Redis 예약을 되돌리고 FAILED 로 남긴다</li>
 * </ol>
 *
 * <p>Redis 가 비워지면 그룹과 스트림이 함께 사라진다. 대기 요청은 그 시점에 잃고, 재고 키 복구
 * ({@code CouponStockRecoveryService})가 {@code quantity − DB 발급 수} 로 다시 세운다 - 같은 Redis 에 있으므로 따로 뺄 항목이 없다
 */
@Slf4j
@Component
public class CouponStreamConsumer implements SmartLifecycle {

    public static final String GROUP = "coupon-issue";

    private static final int PAGE_SIZE = 500;
    private static final long DLQ_MAX_LENGTH = 10_000L;

    /**
     * 스트림 항목 하나. 필드는 접수 Lua 가 쓴 {@code userId}, {@code requestId} 다
     */
    public record StreamEntry(String id, Map<String, String> fields) {

        public static StreamEntry from(MapRecord<String, Object, Object> record) {
            Map<String, String> fields = new LinkedHashMap<>();
            record.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
            return new StreamEntry(record.getId().getValue(), fields);
        }

        String requestId() {
            return fields.get("requestId");
        }

        Long userId() {
            try {
                return Long.valueOf(fields.get("userId"));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        boolean readable() {
            return requestId() != null && !requestId().isBlank() && userId() != null;
        }
    }

    private final StringRedisTemplate redis;
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponStockRedisRepository stockRepository;
    private final CouponIssueBatchWriter batchWriter;

    // 내 쿠폰 목록 캐시(userId 키). searchMyCoupon 이 @Cacheable 로 읽는 목록이라 확정 경로가 비우지 않으면
    // 방금 발급한 쿠폰이 캐시 TTL 동안 목록에 보이지 않는다. 캐시를 끈 구성처럼 없을 수도 있어 선택 주입이다
    @Autowired(required = false)
    @Qualifier("couponCacheManager")
    private CacheManager couponCacheManager;

    // 한 번에 읽어 한 트랜잭션으로 넣는 건수의 상한
    @Value("${coupon.issue.batch-size:500}")
    private int batchSize = 500;
    // 이 시간 넘게 ack 되지 않은 항목은 죽은 인스턴스가 쥔 것으로 보고 가져온다.
    // 처리 중인 인스턴스의 한 배치(최대 한 트랜잭션 10초)보다 길어야 살아 있는 인스턴스의 항목을 가로채지 않는다
    @Value("${coupon.issue.stream.claim-min-idle-ms:30000}")
    private long claimMinIdleMs = 30_000L;
    // 쿠폰마다 유휴 항목 스캔을 돌리는 최소 간격. 0 이면 claim-min-idle-ms 의 절반이다
    @Value("${coupon.issue.stream.claim-interval-ms:0}")
    private long claimIntervalMs = 0L;
    // 항목 하나에 허용하는 배달 횟수. 넘으면 DLQ
    @Value("${coupon.issue.stream.max-deliveries:3}")
    private int maxDeliveries = 3;
    // 새 항목이 없을 때 읽기가 기다리는 시간. 길수록 빈 루프가 줄고 정지 요청에 늦게 반응한다
    @Value("${coupon.issue.stream.block-ms:1000}")
    private long blockMs = 1_000L;
    // 활성 쿠폰 목록을 다시 읽는 간격
    @Value("${coupon.issue.stream.refresh-ms:5000}")
    private long refreshMs = 5_000L;
    // 시험에서 끌 수 있다. false 여도 start() 를 직접 부르면 돈다
    @Value("${coupon.issue.stream.enabled:true}")
    private boolean enabled = true;

    private final String consumerName = resolveConsumerName();
    // 그룹을 이미 만든 쿠폰. NOGROUP 이 나오면 비우고 다시 만든다(Redis 가 비워진 경우)
    private final Set<Long> groupReady = ConcurrentHashMap.newKeySet();
    // 루프가 읽는 쿠폰. 비활성이 됐어도 스트림에 항목이 남아 있으면 비울 때까지 둔다
    private volatile Set<Long> watched = Set.of();
    private final Map<Long, Long> lastClaimScanAt = new ConcurrentHashMap<>();
    private final AtomicLong claimScans = new AtomicLong();
    private volatile long lastRefreshAt = 0L;

    private volatile boolean running = false;
    private Thread loopThread;

    public CouponStreamConsumer(StringRedisTemplate redis, CouponRepository couponRepository,
                                CouponIssueRepository couponIssueRepository,
                                CouponStockRedisRepository stockRepository, CouponIssueBatchWriter batchWriter) {
        this.redis = redis;
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.stockRepository = stockRepository;
        this.batchWriter = batchWriter;
    }

    // 호스트 이름과 프로세스 번호. 같은 호스트에서 재기동하면 이름이 바뀌어 옛 이름의 항목은 유휴 인수로 넘어온다
    private static String resolveConsumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + "-" + ProcessHandle.current().pid();
    }

    public String consumerName() {
        return consumerName;
    }

    // ---- 생명주기 ----

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loopThread = Thread.ofPlatform().name("coupon-stream-consumer").daemon(true).start(this::runLoop);
        log.info("[COUPON_STREAM_CONSUMER_STARTED] consumer={}", consumerName);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        loopThread.interrupt();
        try {
            loopThread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[COUPON_STREAM_CONSUMER_STOPPED] consumer={}", consumerName);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        while (running) {
            try {
                pollOnce();
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                // Redis · DB 일시 오류다. 항목은 ack 하지 않았으므로 PEL 에 남아 유휴 인수가 다시 처리한다
                log.warn("[COUPON_STREAM_LOOP_ERROR] error={}", e.toString());
                sleepQuietly(1_000L);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 한 번의 순회 ----

    /**
     * 루프 한 번 - 유휴 항목을 가져와 처리하고, 모든 감시 쿠폰의 새 항목을 한 번의 블로킹 읽기로 받아 처리한다
     *
     * @return 처리한 항목 수(DLQ 로 보낸 것 포함)
     */
    public int pollOnce() {
        refreshWatchedIfDue();
        Map<String, Long> couponByStream = new LinkedHashMap<>();
        int handled = 0;
        for (Long couponId : watched) {
            ensureGroup(couponId);
            handled += claimAndProcess(couponId);
            couponByStream.put(CouponStockRedisRepository.streamKey(couponId), couponId);
        }
        if (couponByStream.isEmpty()) {
            sleepQuietly(blockMs);
            return handled;
        }
        @SuppressWarnings("unchecked")
        StreamOffset<String>[] offsets = couponByStream.keySet().stream()
                .map(key -> StreamOffset.create(key, ReadOffset.lastConsumed()))
                .toArray(StreamOffset[]::new);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(consumer(),
                StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(blockMs)), offsets);
        if (records == null || records.isEmpty()) {
            return handled;
        }
        Map<Long, List<StreamEntry>> byCoupon = new LinkedHashMap<>();
        for (MapRecord<String, Object, Object> record : records) {
            byCoupon.computeIfAbsent(couponByStream.get(record.getStream()), k -> new ArrayList<>())
                    .add(StreamEntry.from(record));
        }
        for (Map.Entry<Long, List<StreamEntry>> entry : byCoupon.entrySet()) {
            try {
                process(entry.getKey(), entry.getValue());
                handled += entry.getValue().size();
            } catch (RuntimeException e) {
                // 한 쿠폰의 실패가 다른 쿠폰의 처리를 막지 않는다. 항목은 PEL 에 남아 유휴 인수가 다시 처리한다
                log.error("[COUPON_STREAM_PROCESS_FAILED] couponId={}, size={}, error={}",
                        entry.getKey(), entry.getValue().size(), e.toString());
            }
        }
        return handled;
    }

    /**
     * 한 쿠폰만 - 유휴 항목을 가져오고 새 항목을 기다리지 않고 읽어 한 배치로 처리한다. 시험이 루프 없이 부른다
     *
     * @return 처리한 항목 수(DLQ 로 보낸 것 포함)
     * @throws RuntimeException DB 저장이 실패하면 그대로 던진다. 그 항목은 ack 되지 않는다
     */
    public int pollCoupon(Long couponId) {
        ensureGroup(couponId);
        ClaimResult claimed = claimIdle(couponId);
        List<StreamEntry> entries = new ArrayList<>(claimed.processable());
        List<MapRecord<String, Object, Object>> records = withGroup(couponId, () -> redis.opsForStream().read(
                consumer(), StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(CouponStockRedisRepository.streamKey(couponId), ReadOffset.lastConsumed())));
        if (records != null) {
            records.forEach(record -> entries.add(StreamEntry.from(record)));
        }
        if (!entries.isEmpty()) {
            process(couponId, entries);
        }
        return entries.size() + claimed.poisoned();
    }

    private int claimAndProcess(Long couponId) {
        ClaimResult claimed = claimIdle(couponId);
        if (claimed.processable().isEmpty()) {
            return claimed.poisoned();
        }
        try {
            process(couponId, claimed.processable());
        } catch (RuntimeException e) {
            log.error("[COUPON_STREAM_RECLAIM_PROCESS_FAILED] couponId={}, size={}, error={}",
                    couponId, claimed.processable().size(), e.toString());
            return claimed.poisoned();
        }
        return claimed.processable().size() + claimed.poisoned();
    }

    // ---- 그룹 · 감시 쿠폰 ----

    /**
     * 컨슈머 그룹이 없으면 스트림과 함께 만든다. 오프셋 0 으로 만들어 그룹보다 먼저 쌓인 항목도 읽는다 -
     * 접수가 컨슈머보다 먼저 스트림을 만들 수 있고, {@code $} 로 만들면 그 항목이 영영 읽히지 않는다
     */
    public void ensureGroup(Long couponId) {
        if (groupReady.contains(couponId)) {
            return;
        }
        try {
            redis.opsForStream().createGroup(CouponStockRedisRepository.streamKey(couponId),
                    ReadOffset.from("0"), GROUP);
        } catch (RuntimeException e) {
            if (!messageContains(e, "BUSYGROUP")) {
                throw e;
            }
        }
        groupReady.add(couponId);
    }

    private <T> T withGroup(Long couponId, java.util.function.Supplier<T> redisCall) {
        try {
            return redisCall.get();
        } catch (RuntimeException e) {
            if (!messageContains(e, "NOGROUP")) {
                throw e;
            }
            // Redis 가 비워져 그룹이 사라졌다. 다시 만들고 한 번만 재시도한다
            groupReady.remove(couponId);
            ensureGroup(couponId);
            return redisCall.get();
        }
    }

    private static boolean messageContains(Throwable error, String text) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private void refreshWatchedIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshAt < refreshMs) {
            return;
        }
        Set<Long> next = new HashSet<>();
        long lastId = 0L;
        List<Coupon> page;
        do {
            page = couponRepository.findActiveByIdGreaterThan(lastId, PageRequest.of(0, PAGE_SIZE));
            for (Coupon coupon : page) {
                next.add(coupon.getId());
                lastId = coupon.getId();
            }
        } while (page.size() == PAGE_SIZE);
        // 비활성이 된 쿠폰도 스트림에 항목이 남았으면 비울 때까지 읽는다 - 확정되지 못한 요청이 영영 남지 않게
        for (Long id : watched) {
            if (!next.contains(id) && stockRepository.streamLength(id) > 0) {
                next.add(id);
            }
        }
        watched = Set.copyOf(next);
        lastRefreshAt = now;
    }

    private Consumer consumer() {
        return Consumer.from(GROUP, consumerName);
    }

    // ---- 유휴 항목 인수 · 독 항목 ----

    private record ClaimResult(List<StreamEntry> processable, int poisoned) {
    }

    /**
     * 유휴 시간이 지난 항목을 내 이름으로 가져온다 - {@code XAUTOCLAIM … min-idle-time start COUNT n} 을 서버가 거른다
     *
     * <p>유휴 필터가 서버에 있으므로 살아 있는 컨슈머가 쥔 유휴 전 항목이 앞에 많아도 뒤의 유휴 항목이 밀리지 않는다.
     * XAUTOCLAIM 은 배달 횟수를 1 올린다. 올린 뒤의 횟수가 max-deliveries 를 넘으면 처리하지 않고 DLQ 로 보낸다 -
     * 항목은 배달 1 ~ max-deliveries 회째까지 처리를 시도하는 셈이다
     *
     * <p>스캔은 쿠폰마다 {@code claim-interval-ms} 에 한 번만 돈다. 순회마다 PEL 을 훑을 이유가 없다
     */
    private ClaimResult claimIdle(Long couponId) {
        if (!claimScanDue(couponId)) {
            return new ClaimResult(List.of(), 0);
        }
        claimScans.incrementAndGet();
        List<StreamEntry> claimed = autoClaim(couponId);
        if (claimed.isEmpty()) {
            return new ClaimResult(List.of(), 0);
        }
        Map<String, Long> deliveries = deliveryCounts(couponId, claimed);
        List<StreamEntry> processable = new ArrayList<>();
        Map<StreamEntry, Long> poison = new LinkedHashMap<>();
        for (StreamEntry entry : claimed) {
            long count = deliveries.getOrDefault(entry.id(), 1L);
            if (count > maxDeliveries) {
                poison.put(entry, count);
            } else {
                processable.add(entry);
            }
        }
        if (!poison.isEmpty()) {
            deadLetter(couponId, poison);
        }
        return new ClaimResult(processable, poison.size());
    }

    private boolean claimScanDue(Long couponId) {
        long interval = claimIntervalMs > 0 ? claimIntervalMs : claimMinIdleMs / 2;
        long now = System.currentTimeMillis();
        Long last = lastClaimScanAt.get(couponId);
        if (last != null && now - last < interval) {
            return false;
        }
        lastClaimScanAt.put(couponId, now);
        return true;
    }

    /**
     * 처음부터 훑어 batch-size 건까지 가져온다. 한 번의 XAUTOCLAIM 은 PEL 을 일정 수만 훑고 다음 시작점을 돌려주므로,
     * 가져온 것이 batch-size 에 못 미치고 시작점이 남았으면 이어서 부른다
     *
     * <p>Spring Data Redis 3.5 에 XAUTOCLAIM 이 없고 {@code connection.execute} 는 중첩 응답을 평탄화하므로
     * Lettuce 의 {@code xautoclaim} 을 네이티브 연결로 부른다
     */
    private List<StreamEntry> autoClaim(Long couponId) {
        byte[] streamKey = bytes(CouponStockRedisRepository.streamKey(couponId));
        List<StreamEntry> claimed = new ArrayList<>();
        String cursor = "0-0";
        do {
            String start = cursor;
            long want = batchSize - claimed.size();
            ClaimedMessages<byte[], byte[]> reply = withGroup(couponId, () -> redis.execute(
                    (RedisCallback<ClaimedMessages<byte[], byte[]>>) connection -> xautoclaim(connection, streamKey,
                            start, want)));
            if (reply == null) {
                break;
            }
            for (StreamMessage<byte[], byte[]> message : reply.getMessages()) {
                Map<String, String> fields = new LinkedHashMap<>();
                message.getBody().forEach((k, v) -> fields.put(text(k), text(v)));
                claimed.add(new StreamEntry(message.getId(), fields));
            }
            cursor = reply.getId();
        } while (!"0-0".equals(cursor) && claimed.size() < batchSize);
        return claimed;
    }

    @SuppressWarnings("unchecked")
    private ClaimedMessages<byte[], byte[]> xautoclaim(RedisConnection connection, byte[] streamKey, String start,
                                                       long count) {
        RedisAsyncCommands<byte[], byte[]> commands = (RedisAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
        XAutoClaimArgs<byte[]> args = XAutoClaimArgs.Builder
                .xautoclaim(io.lettuce.core.Consumer.from(bytes(GROUP), bytes(consumerName)),
                        Duration.ofMillis(claimMinIdleMs), start)
                .count(count);
        try {
            return commands.xautoclaim(streamKey, args).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("XAUTOCLAIM interrupted", e);
        } catch (ExecutionException e) {
            // NOGROUP 같은 서버 오류를 원인 사슬에 그대로 둔다 - 호출부가 메시지로 판별한다
            throw new IllegalStateException("XAUTOCLAIM failed: " + e.getCause(), e.getCause());
        }
    }

    // 가져온 항목의 배달 횟수(XAUTOCLAIM 이 이미 올린 값). 가져온 id 범위 안의 PEL 한 번으로 읽는다
    private Map<String, Long> deliveryCounts(Long couponId, List<StreamEntry> claimed) {
        String first = claimed.stream().map(StreamEntry::id).min(CouponStreamConsumer::compareIds).orElseThrow();
        String last = claimed.stream().map(StreamEntry::id).max(CouponStreamConsumer::compareIds).orElseThrow();
        PendingMessages pending = withGroup(couponId, () -> redis.opsForStream().pending(
                CouponStockRedisRepository.streamKey(couponId), GROUP, Range.closed(first, last), 100_000L));
        Map<String, Long> counts = new HashMap<>();
        if (pending != null) {
            for (PendingMessage message : pending) {
                counts.put(message.getIdAsString(), message.getTotalDeliveryCount());
            }
        }
        return counts;
    }

    // 스트림 id 는 "밀리초-순번" 이다. 문자열이 아니라 두 수로 비교한다
    private static int compareIds(String a, String b) {
        String[] x = a.split("-");
        String[] y = b.split("-");
        int byTime = Long.compare(Long.parseLong(x[0]), Long.parseLong(y[0]));
        return byTime != 0 ? byTime : Long.compare(Long.parseLong(x[1]), Long.parseLong(y[1]));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Object value) {
        return value instanceof byte[] raw ? new String(raw, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    /** 지금까지 돈 유휴 항목 스캔 횟수. 시험이 스캔 간격을 확인한다 */
    public long claimScans() {
        return claimScans.get();
    }

    /**
     * 배달 횟수를 넘긴 항목을 DLQ 로 옮긴다. 행이 이미 커밋된 요청은 DLQ 가 아니라 성공으로 마감한다 -
     * 커밋은 됐는데 ack 가 계속 실패한 항목이고, 이때 자리를 되돌리면 DB 에는 행이 있는데 재고만 늘어난다
     */
    private void deadLetter(Long couponId, Map<StreamEntry, Long> poison) {
        List<String> requestIds = poison.keySet().stream().map(StreamEntry::requestId)
                .filter(id -> id != null).toList();
        Set<String> committed = requestIds.isEmpty() ? Set.of()
                : new HashSet<>(couponIssueRepository.findExistingRequestIds(requestIds));
        Map<String, String> statuses = new LinkedHashMap<>();
        List<Long> committedUsers = new ArrayList<>();
        for (Map.Entry<StreamEntry, Long> entry : poison.entrySet()) {
            StreamEntry stream = entry.getKey();
            if (stream.requestId() != null && committed.contains(stream.requestId())) {
                statuses.put(stream.requestId(), CouponIssueStatus.SUCCESS);
                committedUsers.add(stream.userId());
                continue;
            }
            Map<String, String> dlqFields = new LinkedHashMap<>(stream.fields());
            dlqFields.put("reason", "max-deliveries-exceeded");
            dlqFields.put("deliveries", String.valueOf(entry.getValue()));
            dlqFields.put("originalId", stream.id());
            addToDlq(couponId, dlqFields);
            if (stream.readable()) {
                stockRepository.rollbackIssue(couponId, stream.userId());
                statuses.put(stream.requestId(), CouponIssueStatus.FAILED_PREFIX + ErrorCode.SERVER_ERROR.name());
            }
            log.error("[COUPON_STREAM_DEAD_LETTER] couponId={}, entryId={}, deliveries={}, fields={}",
                    couponId, stream.id(), entry.getValue(), stream.fields());
        }
        stockRepository.setStatuses(statuses);
        committedUsers.forEach(this::evictMyCouponCache);
        ack(couponId, poison.keySet().stream().map(StreamEntry::id).toList());
    }

    // DLQ 스트림은 길이를 제한한다 - 독 항목이 몰려도 Redis(noeviction)의 메모리를 끝없이 채우지 않는다
    // MAXLEN ~ : 정확한 길이가 아니라 덩어리 단위로 잘라 비용이 싸다
    private void addToDlq(Long couponId, Map<String, String> fields) {
        redis.opsForStream().add(StreamRecords.newRecord().in(CouponStockRedisRepository.dlqKey(couponId)).ofMap(fields),
                XAddOptions.maxlen(DLQ_MAX_LENGTH).approximateTrimming(true));
    }

    // ---- 확정 ----

    /**
     * 한 쿠폰의 항목들을 확정한다. 저장이 실패하면 예외를 던지고 아무것도 ack 하지 않는다
     *
     * <p>읽을 수 없는 항목(userId · requestId 누락)은 확정할 수 없으므로 DLQ 로 보내고 ack 한다 - 되돌릴 예약도 모른다
     */
    public void process(Long couponId, List<StreamEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        List<StreamEntry> unreadable = entries.stream().filter(e -> !e.readable()).toList();
        List<StreamEntry> readable = entries.stream().filter(StreamEntry::readable).toList();
        for (StreamEntry entry : unreadable) {
            Map<String, String> dlqFields = new LinkedHashMap<>(entry.fields());
            dlqFields.put("reason", "unreadable-entry");
            dlqFields.put("originalId", entry.id());
            addToDlq(couponId, dlqFields);
            log.error("[COUPON_STREAM_UNREADABLE_ENTRY] couponId={}, entryId={}, fields={}",
                    couponId, entry.id(), entry.fields());
        }

        // 한 사용자에 항목이 둘이면 앞의 것만 넣는다. 뒤의 것은 소유하지 못해 아래에서 진짜 중복으로 떨어진다
        Map<Long, String> requestIdByUser = new LinkedHashMap<>();
        for (StreamEntry entry : readable) {
            requestIdByUser.putIfAbsent(entry.userId(), entry.requestId());
        }
        InsertOutcome outcome = batchWriter.insertAll(couponId, requestIdByUser);

        // 여기부터 커밋이 끝났다. 아래 단계가 실패해도 항목은 ack 되지 않아 재전달되고, 재전달은 멱등이다
        Map<String, String> statuses = new LinkedHashMap<>();
        List<Long> insertedUsers = new ArrayList<>();
        for (StreamEntry entry : readable) {
            Long userId = entry.userId();
            String requestId = entry.requestId();
            if (!outcome.couponUsable()) {
                // 자리를 배정받았는데 쿠폰이 끝났거나 사라졌다. 되돌리지 않으면 아무도 못 받는 수량이 된다
                stockRepository.rollbackIssue(couponId, userId);
                statuses.put(requestId, CouponIssueStatus.FAILED_PREFIX + ErrorCode.COUPON_NOT_FOUND.name());
            } else if (outcome.owned().contains(requestId)) {
                statuses.put(requestId, CouponIssueStatus.SUCCESS);
                insertedUsers.add(userId);
            } else if (outcome.soldOut().contains(requestId)) {
                // DB 경로와 섞여 Redis 재고가 실제보다 많았다. 자리와 명단을 되돌려 뒤 요청이 판정하게 한다
                stockRepository.rollbackIssue(couponId, userId);
                statuses.put(requestId, CouponIssueStatus.FAILED_PREFIX + ErrorCode.COUPON_OUT_OF_STOCK.name());
            } else {
                // 다른 요청이 이미 그 쿠폰의 행을 갖고 있다 = 진짜 중복이다
                // 같은 requestId 의 행이면 owned 에 들어와 여기 오지 않는다 - 재전달에서 재고를 되돌리면 +1 씩 부푼다
                // 발급자 명단에서는 빼지 않는다 - 빼면 게이트가 그를 다시 통과시켜 같은 실패를 반복한다
                stockRepository.rollbackStockOnly(couponId);
                log.warn("[COUPON_DUPLICATE_BLOCKED_BY_DB] couponId={}, userId={}", couponId, userId);
                statuses.put(requestId, CouponIssueStatus.FAILED_PREFIX + ErrorCode.COUPON_ALREADY_ISSUED.name());
            }
        }
        stockRepository.setStatuses(statuses);
        insertedUsers.forEach(this::evictMyCouponCache);
        ack(couponId, entries.stream().map(StreamEntry::id).toList());
    }

    // 확정된 항목은 ack 하고 스트림에서도 지운다. 지우지 않으면 스트림이 요청 수만큼 자라고,
    // 종료 쿠폰의 고아 예약 회수가 스트림 길이로 미확정 항목을 판정할 수도 없다
    private void ack(Long couponId, List<String> entryIds) {
        String streamKey = CouponStockRedisRepository.streamKey(couponId);
        RecordId[] ids = entryIds.stream().map(RecordId::of).toArray(RecordId[]::new);
        redis.opsForStream().acknowledge(streamKey, GROUP, ids);
        redis.opsForStream().delete(streamKey, ids);
    }

    // 행이 커밋된 사용자의 목록 캐시를 비운다. 캐시 저장소(Redis) 장애로 확정 배치를 재전달시킬 이유는 없다 -
    // 비우지 못하면 TTL 이 지날 때까지 목록이 낡을 뿐이다
    private void evictMyCouponCache(Long userId) {
        if (couponCacheManager == null) {
            return;
        }
        try {
            Cache cache = couponCacheManager.getCache(RedisConfig.COUPON_LIST_CACHE_NAME);
            if (cache != null) {
                cache.evict(userId);
            }
        } catch (Exception e) {
            log.warn("[COUPON_MY_LIST_CACHE_EVICT_FAILED] userId={}, error={}", userId, e.toString());
        }
    }
}
