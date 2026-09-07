package groom.backend.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.Yaml;

/**
 * 배치는 회차 끝에 carried > 0 이면 toss-cancel 을 강제 OPEN 해 다음 회차를 쉬게 한다
 * 그런데 OPEN 유지 시간과 배치 주기(fixedDelay)가 둘 다 회차 끝에서 출발하므로
 * 두 값이 같으면 다음 회차가 OPEN 을 볼지 HALF_OPEN 을 볼지 밀리초 차이로 갈린다
 * (2026-09-07 실측 - 강제 OPEN 9회 중 쉼 2회, 계획서 g11)
 * OPEN 유지 시간이 주기보다 길어야 쉼 회차가 반드시 선다
 */
@DisplayName("toss-cancel OPEN 유지 시간과 배치 주기의 관계")
class TossCancelOpenDurationTest {

    @Test
    @DisplayName("toss-cancel 의 OPEN 유지 시간은 배치 주기(retry-delay-ms)보다 길다")
    @SuppressWarnings("unchecked")
    void openDurationExceedsBatchDelay() throws Exception {
        Map<String, Object> root;
        try (InputStream in = getClass().getResourceAsStream("/application-dev.yml")) {
            assertThat(in).as("application-dev.yml 이 클래스패스에 있어야 한다").isNotNull();
            root = new Yaml().load(in);
        }

        Map<String, Object> cancel = (Map<String, Object>) path(root, "resilience4j", "circuitbreaker", "instances", "toss-cancel");
        Duration openWait = DurationStyle.detectAndParse(String.valueOf(cancel.get("wait-duration-in-open-state")));

        Map<String, Object> compensation = (Map<String, Object>) path(root, "payment", "compensation");
        Duration batchDelay = Duration.ofMillis(Long.parseLong(String.valueOf(compensation.get("retry-delay-ms"))));

        assertThat(openWait)
                .as("OPEN 유지 시간 %s 이 배치 주기 %s 와 같거나 짧으면 강제 OPEN 이 다음 회차를 세우지 못한다",
                        openWait, batchDelay)
                .isGreaterThan(batchDelay);
    }

    private static Object path(Map<String, Object> root, String... keys) {
        Object cur = root;
        for (String key : keys) {
            assertThat(cur).as("경로 %s 가 Map 이어야 한다", key).isInstanceOf(Map.class);
            cur = ((Map<?, ?>) cur).get(key);
            assertThat(cur).as("설정 %s 가 있어야 한다", key).isNotNull();
        }
        return cur;
    }
}
