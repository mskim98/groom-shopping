package groom.backend.interfaces.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.infrastructure.security.JwtRsaTokenProvider;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// 실제 보안 필터 체인에서 uid 토큰의 사용자가 세 가지 principal 주입 방식(user 식 · CustomUserDetails · Authentication 형변환)에 모두 닿는지 본다
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("클레임에서 만든 principal 을 컨트롤러가 받는다")
class PrincipalFromClaimsControllerTest {

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private JwtRsaTokenProvider tokenProvider;
    @Autowired
    private JdbcTemplate jdbc;

    private UserJpaEntity user;
    private String token;
    private String message;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        user = userRepository.save(UserJpaEntity.builder().email("pc-" + unique + "@t.com").password("x")
                .name("pc").role(Role.ROLE_USER).grade(Grade.BRONZE).build());
        token = tokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        message = "claims-" + unique;
        jdbc.update("INSERT INTO notifications (message, is_read, user_id, product_id) VALUES (?, false, ?, ?)",
                message, user.getId(), UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM notifications WHERE user_id = ?", user.getId());
        userRepository.deleteById(user.getId());
    }

    private MockHttpServletRequestBuilder withToken(String path) {
        return get("/api" + path).contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("user 식 주입(내 쿠폰)이 200 이다")
    void myCoupons_uid토큰_200이다() throws Exception {
        mockMvc.perform(withToken("/v1/coupon/me")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("CustomUserDetails 주입(장바구니)이 200 이다")
    void getCart_uid토큰_200이다() throws Exception {
        mockMvc.perform(withToken("/v1/cart")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Authentication 형변환(알림)이 200 이고 그 사용자의 알림만 돌려준다")
    void getNotifications_uid토큰_200이고_그_사용자의_알림이다() throws Exception {
        mockMvc.perform(withToken("/v1/notification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].message").value(message))
                .andExpect(jsonPath("$[0].userId").value(user.getId()));
    }

    @Test
    @DisplayName("토큰이 없으면 401 이다")
    void getCart_토큰없음_401이다() throws Exception {
        mockMvc.perform(get("/api/v1/cart").contextPath("/api")).andExpect(status().isUnauthorized());
    }
}
