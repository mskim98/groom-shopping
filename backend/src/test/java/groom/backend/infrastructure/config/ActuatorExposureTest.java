package groom.backend.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import java.util.UUID;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

// 익명 요청이 /actuator/env 로 환경 값을 읽지 못하고, 측정 도구와 Prometheus 가 읽는 health · prometheus 는 그대로 열려 있는지 본다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// @SpringBootTest 는 지표 내보내기를 꺼 두어 prometheus 엔드포인트가 뜨지 않는다 - 운영과 같게 켠다
@AutoConfigureObservability
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("actuator 노출 범위")
class ActuatorExposureTest {

    // TestRestTemplate 은 서버의 context-path(/api)를 주소 앞에 스스로 붙인다 - 실제 요청 경로는 /api/actuator/...

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("익명 GET /actuator/env 와 /actuator/env/{이름} 은 404 다")
    void env_익명_조회는_404다() {
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/actuator/env/PATH", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/actuator/env/spring.datasource.password", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("익명 GET /actuator/health 와 /actuator/prometheus 는 200 이다")
    void health와_prometheus_익명_조회는_200이다() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        ResponseEntity<String> prometheus = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(health.getStatusCode()).as("health: " + health.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("로그인한 사용자가 없는 경로를 부르면 500 이 아니라 404 다")
    void 없는_경로_인증된_요청은_404다() throws Exception {
        String email = "nf-" + UUID.randomUUID().toString().substring(0, 8) + "@t.com";
        UserJpaEntity user = userRepository.save(UserJpaEntity.builder().email(email)
                .password(passwordEncoder.encode("pw-1234")).name("nf").role(Role.ROLE_USER).grade(Grade.BRONZE).build());
        try {
            HttpHeaders json = new HttpHeaders();
            json.setContentType(MediaType.APPLICATION_JSON);
            String login = rest.postForObject("/v1/auth/login",
                    new HttpEntity<>("{\"email\":\"" + email + "\",\"password\":\"pw-1234\"}", json), String.class);
            HttpHeaders auth = new HttpHeaders();
            auth.setBearerAuth(new ObjectMapper().readTree(login).get("accessToken").asText());

            ResponseEntity<String> response = rest.exchange("/no-such-path", HttpMethod.GET, new HttpEntity<>(auth),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            userRepository.deleteById(user.getId());
        }
    }
}
