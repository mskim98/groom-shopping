package groom.backend.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import groom.backend.application.product.ProductStockRedisRepository;
import groom.backend.domain.product.model.Product;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 상품 상세 2단 캐시. 본문과 수량을 따로 둔다
 *
 * <p>본문: L1(Caffeine 1초) → 포인터 {@code product:{id}:ver} 가 가리키는 값 {@code product:{id}:v{n}}(30초 ± 10%) → DB 가 {@code v{포인터}} 를 채운다.
 * 본문이 바뀌면 커밋 뒤에 새 값 키를 쓰고 → 포인터를 옮기고 → 옛 값 키에 짧은 만료를 건다. 포인터를 먼저 읽고 DB 를 읽으므로 늦게 도착한 옛 본문은 옛 버전 키에만 쓰인다
 * <p>수량: 본문 버전과 무관한 L1(1초) → L2 {@code product:{id}:qty}(값 {@code 재고:재고모드} 한 쌍, {@code product.cache.quantity-l2-ttl-ms} 기본 10초) → DB.
 * 한 쌍을 두는 이유 - 플래시 상품이 REDIS 모드이면 L2 의 재고 대신 살아 있는 {@code product:stock:{id}} 를 읽어 보이고, 그 밖에는 {@code product.stock} 을 보인다.
 * {@code product.stock} · {@code product.stock_mode} 를 바꾸는 쓰기는 커밋 뒤에 {@link StockQuantityInvalidator} 가 L2 항목과 그 인스턴스의 L1 을 지운다
 * <p>수량이 커밋된 변경보다 뒤처지는 최대 시간: 보통 L1 1초(다른 인스턴스의 L1 은 지워지지 않는다). 최악은 커밋 전에 옛 값을 읽은 요청이 제거 뒤에 L2 를 채우는 경우로
 * L2 만료(10초) + L1(1초) = 11초다. L2 만료를 늘리면 이 최악 값이 같은 만큼 늘고 DB 읽기는 그만큼 준다
 * <p>부재: {@code nf:product:{id}} 5초. Redis 읽기 · 쓰기가 예외를 던지면 초당 1줄만 로그를 남기고 DB 가 답한다
 * <p>shortcut: 갱신 두 건의 커밋 뒤 단계가 뒤바뀌면 포인터가 옛 버전을 가리킬 수 있다, 그 값 키 만료(30초) 뒤 DB 가 다시 채워 스스로 풀리므로 순서 보장은 두지 않는다
 */
@Slf4j
@Component
public class ProductDetailCache {

    private static final Duration VALUE_TTL = Duration.ofSeconds(30);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(5);
    // 포인터는 값보다 오래 산다 - 만료돼도 다음 읽기가 새 번호를 받아 DB 에서 채운다
    private static final Duration POINTER_TTL = Duration.ofHours(1);
    private static final Duration SEQ_TTL = Duration.ofHours(24);

    /** 본문과 수량 한 쌍 - 컨트롤러가 status 를 수량에서 파생한다 */
    public record ProductDetail(ProductBody body, int stock) {
    }

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final long graceMs;
    private final Duration quantityL2Ttl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Cache<UUID, ProductBody> bodies;
    private final Cache<UUID, Integer> quantities;
    // 장애 중 읽기마다 로그가 쌓이지 않게 초당 1줄만 남긴다
    private final AtomicLong lastLogMs = new AtomicLong();

    ProductDetailCache(StringRedisTemplate redis, JdbcTemplate jdbc, long l1TtlMs, long graceMs) {
        this(redis, jdbc, l1TtlMs, graceMs, 10_000L);
    }

    @Autowired
    public ProductDetailCache(StringRedisTemplate redis, JdbcTemplate jdbc,
                              @Value("${product.cache.l1-ttl-ms:1000}") long l1TtlMs,
                              @Value("${product.cache.old-version-grace-ms:5000}") long graceMs,
                              @Value("${product.cache.quantity-l2-ttl-ms:10000}") long quantityL2TtlMs) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.graceMs = graceMs;
        this.quantityL2Ttl = Duration.ofMillis(quantityL2TtlMs);
        this.bodies = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(l1TtlMs)).maximumSize(100_000).build();
        this.quantities = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(l1TtlMs)).maximumSize(100_000).build();
    }

    static String pointerKey(UUID id) {
        return "product:" + id + ":ver";
    }

    static String valueKey(UUID id, String version) {
        return "product:" + id + ":v" + version;
    }

    static String sequenceKey(UUID id) {
        return "product:" + id + ":vseq";
    }

    static String quantityKey(UUID id) {
        return "product:" + id + ":qty";
    }

    static String negativeKey(UUID id) {
        return "nf:product:" + id;
    }

    /** 본문과 수량을 읽는다. 상품이 없으면 빈 값이다. loader 는 L1 · L2 가 모두 비었을 때만 부른다 */
    public Optional<ProductDetail> read(UUID id, Function<UUID, Optional<Product>> loader) {
        Optional<ProductBody> body = readBody(id, loader);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        OptionalInt stock = quantity(id, body.get());
        if (stock.isEmpty()) {
            // 다른 인스턴스가 지운 상품 - 이 인스턴스의 L1 본문이 남아 있어도 없는 상품이다
            bodies.invalidate(id);
            writeQuietly(() -> redis.opsForValue().set(negativeKey(id), "1", NEGATIVE_TTL), id);
            return Optional.empty();
        }
        return Optional.of(new ProductDetail(body.get(), stock.getAsInt()));
    }

    private Optional<ProductBody> readBody(UUID id, Function<UUID, Optional<Product>> loader) {
        ProductBody cached = bodies.getIfPresent(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        String pointer = null;
        try {
            if (Boolean.TRUE.equals(redis.hasKey(negativeKey(id)))) {
                return Optional.empty();
            }
            pointer = pointerOf(id);
            String json = redis.opsForValue().get(valueKey(id, pointer));
            if (json != null) {
                ProductBody body = mapper.readValue(json, ProductBody.class);
                bodies.put(id, body);
                return Optional.of(body);
            }
        } catch (RuntimeException | JsonProcessingException e) {
            logOnce("[PRODUCT_CACHE_READ_FAILED] id={} cause={}", id, e.toString());
            pointer = null;
        }
        Optional<Product> loaded = loader.apply(id);
        if (loaded.isEmpty()) {
            writeQuietly(() -> redis.opsForValue().set(negativeKey(id), "1", NEGATIVE_TTL), id);
            return Optional.empty();
        }
        ProductBody body = ProductBody.from(loaded.get());
        bodies.put(id, body);
        String fillPointer = pointer;
        if (fillPointer != null) {
            writeQuietly(() -> redis.opsForValue().set(valueKey(id, fillPointer), json(body), jitter(VALUE_TTL)), id);
        }
        return Optional.of(body);
    }

    // 포인터가 없으면 새 번호를 받아 만든다(동시에 만들면 먼저 쓴 쪽이 이긴다)
    private String pointerOf(UUID id) {
        String pointer = redis.opsForValue().get(pointerKey(id));
        if (pointer != null) {
            return pointer;
        }
        Long next = redis.opsForValue().increment(sequenceKey(id));
        redis.expire(sequenceKey(id), SEQ_TTL);
        redis.opsForValue().setIfAbsent(pointerKey(id), String.valueOf(next), POINTER_TTL);
        return redis.opsForValue().get(pointerKey(id));
    }

    // 30초 ± 10% - 같은 시각에 채워진 키가 한꺼번에 만료돼 DB 로 몰리지 않게 한다
    private static Duration jitter(Duration base) {
        double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return Duration.ofMillis((long) (base.toMillis() * factor));
    }

    private String json(ProductBody body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private OptionalInt quantity(UUID id, ProductBody body) {
        Integer cached = quantities.getIfPresent(id);
        if (cached != null) {
            return OptionalInt.of(cached);
        }
        OptionalInt stock = loadQuantity(id, body);
        stock.ifPresent(value -> quantities.put(id, value));
        return stock;
    }

    // 행이 없으면 빈 값이다(예외로 올리지 않는다)
    private OptionalInt loadQuantity(UUID id, ProductBody body) {
        String[] pair = readPair(id);
        if (pair == null) {
            return OptionalInt.empty();
        }
        if (body.flashSale() && "REDIS".equals(pair[1])) {
            try {
                String live = redis.opsForValue().get(ProductStockRedisRepository.STOCK_KEY_PREFIX + id);
                if (live != null) {
                    return OptionalInt.of(Integer.parseInt(live));
                }
            } catch (RuntimeException e) {
                logOnce("[PRODUCT_CACHE_READ_FAILED] id={} cause={}", id, e.toString());
            }
        }
        return OptionalInt.of(Integer.parseInt(pair[0]));
    }

    // {재고, 재고모드} 한 쌍. L2 → DB 순이고 DB 에서 읽으면 L2 를 채운다. 행이 없으면 null
    private String[] readPair(UUID id) {
        try {
            String cached = redis.opsForValue().get(quantityKey(id));
            if (cached != null) {
                String[] pair = cached.split(":", 2);
                Integer.parseInt(pair[0]);
                if (pair.length == 2) {
                    return pair;
                }
            }
        } catch (RuntimeException e) {
            logOnce("[PRODUCT_CACHE_READ_FAILED] id={} cause={}", id, e.toString());
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT stock, stock_mode FROM product WHERE id = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        String[] pair = {String.valueOf(((Number) rows.get(0).get("stock")).intValue()), String.valueOf(rows.get(0).get("stock_mode"))};
        writeQuietly(() -> redis.opsForValue().set(quantityKey(id), pair[0] + ":" + pair[1], quantityL2Ttl), id);
        return pair;
    }

    /** product.stock · stock_mode 쓰기가 커밋된 뒤에 부른다 - L2 항목과 이 인스턴스의 수량 L1 을 지운다 */
    public void evictQuantity(UUID id) {
        quantities.invalidate(id);
        try {
            redis.delete(quantityKey(id));
        } catch (RuntimeException e) {
            logOnce("[PRODUCT_CACHE_WRITE_FAILED] id={} cause={}", id, e.toString());
        }
    }

    /** 커밋 뒤에 부른다 - 새 값 키 → 포인터 이동 → 옛 값 키 짧은 만료 순서다. 이 인스턴스의 L1 도 비운다 */
    public void switchVersion(UUID id, Product product) {
        bodies.invalidate(id);
        try {
            Long next = redis.opsForValue().increment(sequenceKey(id));
            redis.expire(sequenceKey(id), SEQ_TTL);
            redis.opsForValue().set(valueKey(id, String.valueOf(next)), json(ProductBody.from(product)), jitter(VALUE_TTL));
            String old = redis.opsForValue().getAndSet(pointerKey(id), String.valueOf(next));
            redis.expire(pointerKey(id), POINTER_TTL);
            if (old != null) {
                redis.expire(valueKey(id, old), Duration.ofMillis(graceMs));
            }
            redis.delete(negativeKey(id));
        } catch (RuntimeException e) {
            logOnce("[PRODUCT_CACHE_WRITE_FAILED] id={} cause={}", id, e.toString());
        }
    }

    /** 삭제가 커밋된 뒤 - 포인터를 지우고 부재 키를 쓴다. 옛 값 키는 짧은 만료로 정리한다 */
    public void evictDeleted(UUID id) {
        bodies.invalidate(id);
        quantities.invalidate(id);
        try {
            String old = redis.opsForValue().get(pointerKey(id));
            redis.opsForValue().set(negativeKey(id), "1", NEGATIVE_TTL);
            redis.delete(pointerKey(id));
            redis.delete(quantityKey(id));
            if (old != null) {
                redis.expire(valueKey(id, old), Duration.ofMillis(graceMs));
            }
        } catch (RuntimeException e) {
            logOnce("[PRODUCT_CACHE_WRITE_FAILED] id={} cause={}", id, e.toString());
        }
    }

    // 시험이 수량 L1 만 비운 상태(본문 L1 은 남음)를 만든다 - 운영에서는 두 항목의 쓰기 시각이 달라 만료 시점이 어긋날 수 있다
    void invalidateQuantity(UUID id) {
        quantities.invalidate(id);
    }

    /** 상품을 만든 뒤 - 그 id 로 먼저 읽혀 남은 부재 키를 지운다 */
    public void clearNegative(UUID id) {
        try {
            redis.delete(negativeKey(id));
        } catch (RuntimeException e) {
            logOnce("[PRODUCT_CACHE_WRITE_FAILED] id={} cause={}", id, e.toString());
        }
    }

    private void writeQuietly(Runnable write, UUID id) {
        try {
            write.run();
        } catch (RuntimeException e) {
            logOnce("[PRODUCT_CACHE_WRITE_FAILED] id={} cause={}", id, e.toString());
        }
    }

    private void logOnce(String format, Object a, Object b) {
        long now = System.currentTimeMillis();
        long last = lastLogMs.get();
        if (now - last >= 1000 && lastLogMs.compareAndSet(last, now)) {
            log.warn(format, a, b);
        }
    }
}
