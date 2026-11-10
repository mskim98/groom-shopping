package groom.backend.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import groom.backend.domain.auth.enums.Role;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.userdetails.UserDetailsService;

@DisplayName("JWT 서명 키 로딩(T3-12)")
class JwtRsaTokenProviderKeyTest {

    private final UserDetailsService users = mock(UserDetailsService.class);

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
                + "\n-----END " + label + "-----";
    }

    private static KeyPair newPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private JwtRsaTokenProvider provider(MockEnvironment env, String priv, String pub, String file) throws Exception {
        return new JwtRsaTokenProvider(users, env, priv, pub, file, 60_000L, 60_000L);
    }

    @Test
    @DisplayName("키 설정이 없으면 dev 에서는 임시 키 쌍을 만들어 토큰을 서명·검증한다")
    void 키없음_dev_임시키쌍으로_토큰을_서명하고_검증한다() throws Exception {
        JwtRsaTokenProvider provider = provider(new MockEnvironment(), "", "", "");

        String token = provider.createAccessToken(1L, "a@b.c", Role.ROLE_USER);

        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("키 설정이 없으면 prod 에서는 기동을 막는다")
    void 키없음_prod_기동을_막는다() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> provider(prod, "", "", "")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("env 로 넣은 \\n 문자열 PEM 과 base64 로 감싼 PEM 을 모두 읽는다")
    void env_한줄_PEM_두형식을_읽는다() throws Exception {
        KeyPair pair = newPair();
        String priv = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String pub = pem("PUBLIC KEY", pair.getPublic().getEncoded());

        JwtRsaTokenProvider escaped = provider(new MockEnvironment(),
                priv.replace("\n", "\\n"), pub.replace("\n", "\\n"), "");
        JwtRsaTokenProvider wrapped = provider(new MockEnvironment(),
                Base64.getEncoder().encodeToString(priv.getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(pub.getBytes(StandardCharsets.UTF_8)), "");

        assertThat(escaped.validateToken(escaped.createAccessToken(1L, "a@b.c", Role.ROLE_USER))).isTrue();
        assertThat(wrapped.validateToken(escaped.createAccessToken(1L, "a@b.c", Role.ROLE_USER)))
                .as("같은 키 쌍이면 서로의 토큰을 검증한다").isTrue();
    }

    @Test
    @DisplayName("PEM 묶음 파일 경로로도 키를 읽는다")
    void key_file_PEM묶음을_읽는다(@TempDir Path dir) throws Exception {
        KeyPair pair = newPair();
        Path file = dir.resolve("jwt-keys.pem");
        Files.writeString(file, pem("PRIVATE KEY", pair.getPrivate().getEncoded()) + "\n"
                + pem("PUBLIC KEY", pair.getPublic().getEncoded()) + "\n");

        JwtRsaTokenProvider provider = provider(new MockEnvironment(), "", "", file.toString());

        assertThat(provider.validateToken(provider.createAccessToken(1L, "a@b.c", Role.ROLE_USER))).isTrue();
    }

    @Test
    @DisplayName("한쪽 키만 설정하면 기동을 막는다")
    void 한쪽키만_설정하면_기동을_막는다() throws Exception {
        KeyPair pair = newPair();

        assertThatThrownBy(() -> provider(new MockEnvironment(),
                pem("PRIVATE KEY", pair.getPrivate().getEncoded()), "", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
