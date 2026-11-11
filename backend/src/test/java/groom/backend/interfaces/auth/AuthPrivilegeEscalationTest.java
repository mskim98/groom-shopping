package groom.backend.interfaces.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

// 가입 · 프로필 수정 요청 본문의 role · grade 로 권한을 올릴 수 없는지 본다
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("가입 · 프로필 수정의 권한 상승 차단")
class AuthPrivilegeEscalationTest {

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

    private String email;

    @BeforeEach
    void setUp() {
        email = "esc-" + UUID.randomUUID().toString().substring(0, 8) + "@t.com";
    }

    @AfterEach
    void tearDown() {
        var keys = redisTemplate.keys("*" + email + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        userRepository.findByEmail(email).ifPresent(u -> userRepository.deleteById(u.getId()));
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"pw-1234\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    private static String roleClaim(String token) throws Exception {
        String body = token.split("\\.")[1];
        JsonNode claims = JSON.readTree(new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8));
        return claims.get("role").asText();
    }

    private String accessTokenOf(MvcResult login) throws Exception {
        return JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UserJpaEntity seedUser() {
        return userRepository.save(UserJpaEntity.builder().email(email)
                .password(passwordEncoder.encode("pw-1234")).name("원래이름").role(Role.ROLE_USER).grade(Grade.BRONZE)
                .build());
    }

    @Test
    @DisplayName("가입 본문에 ROLE_ADMIN 과 VIP 를 넣어도 ROLE_USER · BRONZE 로 만들어지고 로그인 토큰도 ROLE_USER 다")
    void signup_본문에_관리자와_VIP를_넣어도_USER와_BRONZE로_만들어진다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"pw-1234\",\"name\":\"가입자\","
                                + "\"role\":\"ROLE_ADMIN\",\"grade\":\"VIP\"}"))
                .andExpect(status().is2xxSuccessful());

        UserJpaEntity saved = userRepository.findByEmail(email).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(saved.getGrade()).isEqualTo(Grade.BRONZE);
        assertThat(roleClaim(accessTokenOf(login()))).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("프로필 수정 본문에 ROLE_ADMIN 과 높은 등급을 넣어도 이름만 바뀌고 로그인 · 리프레시 토큰은 ROLE_USER 다")
    void updateUser_본문에_관리자와_높은_등급을_넣어도_이름만_바뀐다() throws Exception {
        seedUser();
        MvcResult login = login();

        mockMvc.perform(put("/api/v1/auth/users").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accessTokenOf(login))
                        .content("{\"name\":\"바뀐이름\",\"role\":\"ROLE_ADMIN\",\"grade\":\"VIP\"}"))
                .andExpect(status().isOk());

        UserJpaEntity after = userRepository.findByEmail(email).orElseThrow();
        assertThat(after.getName()).isEqualTo("바뀐이름");
        assertThat(after.getRole()).isEqualTo(Role.ROLE_USER);
        assertThat(after.getGrade()).isEqualTo(Grade.BRONZE);

        assertThat(roleClaim(accessTokenOf(login()))).isEqualTo("ROLE_USER");
        Cookie refreshCookie = login.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE_NAME);
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh").contextPath("/api").cookie(refreshCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(roleClaim(accessTokenOf(refreshed))).isEqualTo("ROLE_USER");
    }
}
