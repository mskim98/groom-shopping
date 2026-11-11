package groom.backend.interfaces.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.infrastructure.product.StockWarmUpRunner;
import groom.backend.interfaces.auth.persistence.SpringDataUserRepository;
import groom.backend.interfaces.auth.persistence.UserJpaEntity;
import groom.backend.interfaces.product.persistence.ProductJpaEntity;
import groom.backend.interfaces.product.persistence.SpringDataProductRepository;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

// 상품 생성 · 수정 · 재고 증감 · 삭제가 관리자만 되고, 조회는 로그인 없이 되는지 본다 (권한 검사 AOP 까지 타도록 필터 체인을 쓴다)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.kafka.streams.auto-startup=false",
        "payment.consumer.auto-startup=false"
})
@DisplayName("상품 변경 API 는 관리자 전용")
class ProductAdminOnlyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @MockBean
    private StockWarmUpRunner stockWarmUpRunner;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SpringDataUserRepository userRepository;
    @Autowired
    private SpringDataProductRepository productRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> emails = new ArrayList<>();
    private String marker;
    private UUID productId;
    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        marker = "adm-" + UUID.randomUUID().toString().substring(0, 8);
        productId = productRepository.save(ProductJpaEntity.builder().id(UUID.randomUUID()).name(marker).price(1000)
                .stock(10).isActive(true).category("GENERAL").status("AVAILABLE").build()).getId();
        userToken = loginAs(Role.ROLE_USER);
        adminToken = loginAs(Role.ROLE_ADMIN);
    }

    @AfterEach
    void tearDown() {
        for (String email : emails) {
            var keys = redisTemplate.keys("*" + email + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            userRepository.findByEmail(email).ifPresent(u -> userRepository.deleteById(u.getId()));
        }
        jdbc.update("DELETE FROM product WHERE name LIKE ?", marker + "%");
    }

    private String loginAs(Role role) throws Exception {
        String email = role.name().toLowerCase() + "-" + marker + "@t.com";
        emails.add(email);
        userRepository.save(UserJpaEntity.builder().email(email).password(passwordEncoder.encode("pw-1234"))
                .name("tester").role(role).grade(Grade.BRONZE).build());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"pw-1234\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId);
    }

    private int priceAndName() {
        return jdbc.queryForObject("SELECT price FROM product WHERE id = ? AND name = ?", Integer.class,
                productId, marker);
    }

    private int productRowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM product WHERE name LIKE ?", Integer.class, marker + "%");
    }

    private RequestBuilder createRequest(String token) {
        MockMultipartFile json = new MockMultipartFile("product", "", "application/json",
                ("{\"name\":\"" + marker + "-new\",\"price\":500,\"stock\":3,\"category\":\"GENERAL\"}").getBytes());
        return multipart("/api/v1/product").file(json).contextPath("/api").header("Authorization", token);
    }

    @Test
    @DisplayName("ROLE_USER 토큰의 생성 · 수정 · 재고 증가 · 재고 감소 · 삭제는 모두 403 ACCESS_DENIED 이고 상품 행은 그대로다")
    void 상품변경_USER토큰_모두_403이고_아무것도_바뀌지_않는다() throws Exception {
        mockMvc.perform(createRequest(userToken)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(patch("/api/v1/product/{id}", productId).contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":1}")
                        .header("Authorization", userToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(patch("/api/v1/product/{id}/stock/increase", productId).contextPath("/api")
                        .param("amount", "5").header("Authorization", userToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(patch("/api/v1/product/{id}/stock/decrease", productId).contextPath("/api")
                        .param("amount", "5").header("Authorization", userToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(delete("/api/v1/product/{id}", productId).contextPath("/api")
                        .header("Authorization", userToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(stock()).isEqualTo(10);
        assertThat(priceAndName()).isEqualTo(1000);
        assertThat(productRowCount()).as("생성도 삭제도 되지 않아 행이 하나다").isEqualTo(1);
    }

    @Test
    @DisplayName("ROLE_ADMIN 토큰은 생성 · 수정 · 재고 증가 · 재고 감소 · 삭제가 권한 검사를 통과한다")
    void 상품변경_ADMIN토큰_권한검사를_통과한다() throws Exception {
        mockMvc.perform(createRequest(adminToken)).andExpect(status().is2xxSuccessful());
        mockMvc.perform(patch("/api/v1/product/{id}", productId).contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":2000}")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk());
        assertThat(priceAndName()).isEqualTo(2000);
        mockMvc.perform(patch("/api/v1/product/{id}/stock/increase", productId).contextPath("/api")
                        .param("amount", "5").header("Authorization", adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/product/{id}/stock/decrease", productId).contextPath("/api")
                        .param("amount", "3").header("Authorization", adminToken))
                .andExpect(status().isOk());
        assertThat(stock()).isEqualTo(12);
        mockMvc.perform(delete("/api/v1/product/{id}", productId).contextPath("/api")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("상품 조회 GET 은 토큰 없이도 200 이다")
    void 상품조회_토큰없이_200이다() throws Exception {
        mockMvc.perform(get("/api/v1/product/{id}", productId).contextPath("/api"))
                .andExpect(status().isOk());
    }
}
