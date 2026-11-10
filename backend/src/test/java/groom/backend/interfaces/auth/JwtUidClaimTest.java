package groom.backend.interfaces.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.infrastructure.security.CookieUtil;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// 로그인과 리프레시가 만든 토큰 4개가 모두 users.id 와 같은 숫자 클레임 uid 를 갖고, sub · role · type 은 그대로인지 본다
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("토큰의 uid 클레임")
class JwtUidClaimTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private UserJpaEntity user;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        user = userRepository.save(UserJpaEntity.builder().email("uid-" + unique + "@t.com")
                .password(passwordEncoder.encode("pw-1234")).name("uid").role(Role.ROLE_USER).grade(Grade.BRONZE)
                .build());
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(redisTemplate.keys("*" + user.getEmail() + "*"));
        userRepository.deleteById(user.getId());
    }

    // 서명 검증은 이 시험의 대상이 아니라 본문(payload)만 푼다
    private static JsonNode payload(String token) throws Exception {
        String body = token.split("\\.")[1];
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8));
    }

    private void assertClaims(String token, String type) throws Exception {
        JsonNode claims = payload(token);
        assertThat(claims.get("uid")).as("uid 클레임").isNotNull();
        assertThat(claims.get("uid").isNumber()).as("uid 는 숫자").isTrue();
        assertThat(claims.get("uid").asLong()).isEqualTo(user.getId());
        assertThat(claims.get("sub").asText()).isEqualTo(user.getEmail());
        assertThat(claims.get("role").asText()).isEqualTo("ROLE_USER");
        assertThat(claims.get("type").asText()).isEqualTo(type);
    }

    @Test
    @DisplayName("로그인이 만든 액세스 · 리프레시 토큰과 리프레시가 만든 두 토큰이 모두 uid 를 갖는다")
    void 로그인과_리프레시_토큰_네개_모두_uid를_갖는다() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"pw-1234\"}"))
                .andExpect(status().isOk()).andReturn();
        String access = JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        Cookie refreshCookie = login.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE_NAME);
        assertThat(refreshCookie).isNotNull();

        assertClaims(access, "access");
        assertClaims(refreshCookie.getValue(), "refresh");

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").contextPath("/api").cookie(refreshCookie))
                .andExpect(status().isOk()).andReturn();
        String newAccess = JSON.readTree(refreshed.getResponse().getContentAsString()).get("accessToken").asText();
        Cookie newRefresh = refreshed.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE_NAME);
        assertThat(newRefresh).isNotNull();

        assertClaims(newAccess, "access");
        assertClaims(newRefresh.getValue(), "refresh");
    }
}
