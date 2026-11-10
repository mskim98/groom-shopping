package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

// 재고 원장 배치 주기 · 복귀 대기 시간 · Redis 서킷 설정이 설계 값을 지키는지 application-dev.yml 에서 직접 읽어 고정한다
@DisplayName("재고 원장 설정 값")
class StockLedgerPolicyTest {

    private static Map<String, Object> root;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        try (InputStream in = StockLedgerPolicyTest.class.getResourceAsStream("/application-dev.yml")) {
            // 문서 구분(---)이 있어 첫 문서가 아니라 전체를 읽어 병합한다
            root = new java.util.HashMap<>();
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map<?, ?> map) {
                    root.putAll((Map<String, Object>) map);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object at(String path) {
        Object node = root;
        for (String key : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = ((Map<String, Object>) map).get(key);
        }
        return node;
    }

    @Test
    @DisplayName("stock.ledger.apply-interval-ms 는 1000 이다")
    void 원장적용_주기는_1000ms다() {
        assertThat(at("stock.ledger.apply-interval-ms")).isEqualTo(1000);
    }

    @Test
    @DisplayName("stock.mode.min-db-dwell-ms 는 70000 이고 Toss 응답 한도(http.client.toss.response-timeout)보다 크다")
    void DB모드_최소대기는_70000ms이고_Toss_응답한도보다_크다() {
        Object dwell = at("stock.mode.min-db-dwell-ms");
        Duration tossTimeout = Duration.parse("PT" + String.valueOf(at("http.client.toss.response-timeout")).toUpperCase());

        assertThat(dwell).isEqualTo(70000);
        assertThat(((Number) dwell).longValue()).isGreaterThan(tossTimeout.toMillis());
    }

    @Test
    @DisplayName("resilience4j.circuitbreaker.instances.redis-stock 이 있다")
    void redis_stock_서킷_인스턴스가_있다() {
        assertThat(at("resilience4j.circuitbreaker.instances.redis-stock")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("stock 아래에 차감 전략을 고르는 키는 없고 원장 · 모드 · 대조 · 워밍업 설정만 있다")
    void 재고_설정에_전략_키는_없다() {
        assertThat(at("stock")).isInstanceOf(Map.class);
        assertThat(new java.util.HashSet<Object>(((Map<?, ?>) at("stock")).keySet())).isSubsetOf("ledger", "mode", "reconcile", "warmup");
    }
}
