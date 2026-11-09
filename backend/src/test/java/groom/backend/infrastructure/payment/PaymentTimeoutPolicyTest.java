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
 * 결제 승인의 시간 설정을 application-dev.yml 에 고정한다. 근거는 yml 의 주석과 Toss 문서에 있다
 * - 응답 한도 60초: https://docs.tosspayments.com/resources/glossary/timeout (결제 API 읽기 시간 초과 60초)
 * - 멱등키 재시도: https://docs.tosspayments.com/reference/using-api/idempotency-key
 */
@DisplayName("결제 타임아웃 정책 설정값")
class PaymentTimeoutPolicyTest {

    @Test
    @DisplayName("Toss 응답 한도는 60초다")
    void Toss_응답_한도는_60초다() {
        assertThat(duration("http", "client", "toss", "response-timeout")).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("Toss 경로당 최대 커넥션은 50이다")
    void Toss_경로당_최대_커넥션은_50이다() {
        assertThat(number("http", "client", "toss", "max-per-route")).isEqualTo(50L);
    }

    @Test
    @DisplayName("Toss 재시도는 최대 2회다")
    void Toss_재시도는_최대_2회다() {
        assertThat(number("resilience4j", "retry", "instances", "toss", "max-attempts")).isEqualTo(2L);
    }

    @Test
    @DisplayName("Toss 서킷 느린호출 기준은 10초다")
    void Toss_서킷_느린호출_기준은_10초다() {
        assertThat(duration("resilience4j", "circuitbreaker", "instances", "toss", "slow-call-duration-threshold"))
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("Hikari 커넥션 대기는 10000ms다")
    void Hikari_커넥션_대기는_10000ms다() {
        assertThat(number("spring", "datasource", "hikari", "connection-timeout")).isEqualTo(10000L);
    }

    @Test
    @DisplayName("결제 outbox 폴링 주기는 200ms다")
    void 결제_outbox_폴링_주기는_200ms다() {
        assertThat(number("payment", "outbox", "poll-delay-ms")).isEqualTo(200L);
    }

    @Test
    @DisplayName("결제 확정 대기 상한은 10000ms다")
    void 결제_확정_대기_상한은_10000ms다() {
        assertThat(number("payment", "confirm", "wait-timeout-ms")).isEqualTo(10000L);
    }

    @Test
    @DisplayName("결제 확정 폴링 간격은 200ms다")
    void 결제_확정_폴링_간격은_200ms다() {
        assertThat(number("payment", "confirm", "poll-interval-ms")).isEqualTo(200L);
    }

    @Test
    @DisplayName("대기 상한은 Toss 응답 한도보다 짧다")
    void 대기_상한은_Toss_응답_한도보다_짧다() {
        Duration wait = Duration.ofMillis(number("payment", "confirm", "wait-timeout-ms"));

        assertThat(wait).isLessThan(duration("http", "client", "toss", "response-timeout"));
    }

    private static Duration duration(String... keys) {
        return DurationStyle.detectAndParse(String.valueOf(value(keys)));
    }

    private static long number(String... keys) {
        return Long.parseLong(String.valueOf(value(keys)));
    }

    private static Object value(String... keys) {
        Map<String, Object> root;
        try (InputStream in = PaymentTimeoutPolicyTest.class.getResourceAsStream("/application-dev.yml")) {
            assertThat(in).as("application-dev.yml 이 클래스패스에 있어야 한다").isNotNull();
            root = new Yaml().load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        Object cur = root;
        for (String key : keys) {
            assertThat(cur).as("경로 %s 가 Map 이어야 한다", key).isInstanceOf(Map.class);
            cur = ((Map<?, ?>) cur).get(key);
            assertThat(cur).as("설정 %s 가 있어야 한다", String.join(".", keys)).isNotNull();
        }
        return cur;
    }
}
