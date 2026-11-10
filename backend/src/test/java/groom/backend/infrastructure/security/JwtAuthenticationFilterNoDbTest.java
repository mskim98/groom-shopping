package groom.backend.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import groom.backend.application.auth.service.TokenBlacklistService;
import groom.backend.domain.auth.entity.User;
import groom.backend.domain.auth.enums.Grade;
import groom.backend.domain.auth.enums.Role;
import groom.backend.domain.auth.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

// uid 가 있는 액세스 토큰은 DB 를 거치지 않고 인증되고, 서명 검증은 요청당 1번이며, uid 없는 옛 토큰은 이메일 조회 1번으로 인증된다
@DisplayName("JWT 인증 필터 - DB 조회 없음")
class JwtAuthenticationFilterNoDbTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TokenBlacklistService blacklist = mock(TokenBlacklistService.class);

    private JwtRsaTokenProvider provider;
    private KeyPair pair;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
        Path file = dir.resolve("jwt-keys.pem");
        Files.writeString(file, pem("PRIVATE KEY", pair.getPrivate().getEncoded()) + "\n"
                + pem("PUBLIC KEY", pair.getPublic().getEncoded()) + "\n");
        provider = spy(new JwtRsaTokenProvider(new CustomUserDetailsService(userRepository), new MockEnvironment(),
                "", "", file.toString(), 600_000L, 600_000L));
        filter = new JwtAuthenticationFilter(provider, blacklist);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
                + "\n-----END " + label + "-----";
    }

    private MockHttpServletResponse call(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/cart");
        request.setRequestURI("/api/v1/cart");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("uid 가 있는 토큰은 사용자 조회 없이 클레임의 id · 이메일 · 역할로 인증하고 서명은 1번만 검증한다")
    void doFilter_uid토큰_DB없이_인증하고_서명은_한번만_검증한다() throws Exception {
        String token = provider.createAccessToken(42L, "claims@t.com", Role.ROLE_ADMIN);
        clearInvocations(provider);

        call(token);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        User user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
        assertThat(user.getId()).isEqualTo(42L);
        assertThat(user.getEmail()).isEqualTo("claims@t.com");
        assertThat(user.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
        verifyNoInteractions(userRepository);
        verify(provider, times(1)).parseClaims(anyString());
    }

    @Test
    @DisplayName("uid 가 없는 옛 토큰은 이메일로 사용자를 1번 조회해 인증한다")
    void doFilter_uid없는_토큰_이메일로_한번_조회해_인증한다() throws Exception {
        String legacy = Jwts.builder().setSubject("old@t.com").claim("role", Role.ROLE_USER).claim("type", "access")
                .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(pair.getPrivate(), SignatureAlgorithm.RS256).compact();
        User stored = new User(7L, "old@t.com", "pw", "옛사용자", Role.ROLE_USER, Grade.BRONZE, null, null);
        when(userRepository.findByEmail("old@t.com")).thenReturn(Optional.of(stored));

        call(legacy);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(((CustomUserDetails) authentication.getPrincipal()).getUser().getId()).isEqualTo(7L);
        verify(userRepository, times(1)).findByEmail("old@t.com");
    }

    @Test
    @DisplayName("블랙리스트에 오른 토큰은 401 이고 인증이 만들어지지 않는다")
    void doFilter_블랙리스트_토큰_401이고_인증이_없다() throws Exception {
        String token = provider.createAccessToken(42L, "claims@t.com", Role.ROLE_USER);
        when(blacklist.isBlacklisted(token)).thenReturn(true);

        MockHttpServletResponse response = call(token);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("리프레시 토큰을 액세스 토큰 자리에 쓰면 인증이 만들어지지 않는다")
    void doFilter_리프레시토큰_인증이_없다() throws Exception {
        String refresh = provider.createRefreshToken(42L, "claims@t.com", Role.ROLE_USER);

        call(refresh);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }
}
