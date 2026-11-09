package groom.backend.infrastructure.security;

import groom.backend.domain.auth.enums.Role;
import io.jsonwebtoken.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

@Slf4j
@Component
public class JwtRsaTokenProvider {

    private final UserDetailsService userDetailsService;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final long accessTokenValidityInMilliseconds;
    private final long refreshTokenValidityInMilliseconds;

    public JwtRsaTokenProvider(UserDetailsService userDetailsService,
                               Environment environment,
                               @Value("${jwt.security.privateKey:}") String privateKeyPem,
                               @Value("${jwt.security.publicKey:}") String publicKeyPem,
                               @Value("${jwt.security.key-file:}") String keyFile,
                               @Value("${jwt.access.expiration}") long accessTokenValidity,
                               @Value("${jwt.refresh.expiration}") long refreshTokenValidity) throws Exception {
        this.userDetailsService = userDetailsService;
        String privatePem = normalize(privateKeyPem);
        String publicPem = normalize(publicKeyPem);
        if (privatePem.isBlank() && publicPem.isBlank() && !keyFile.isBlank()) {
            String bundle = Files.readString(Path.of(keyFile));
            privatePem = extractBlock(bundle, "PRIVATE KEY");
            publicPem = extractBlock(bundle, "PUBLIC KEY");
        }
        if (privatePem.isBlank() != publicPem.isBlank()) {
            throw new IllegalStateException("JWT 개인키와 공개키는 둘 다 설정해야 한다(JWT_PRIVATE_KEY · JWT_PUBLIC_KEY)");
        }
        if (privatePem.isBlank()) {
            // 키가 하나도 없을 때 prod 가 임시 키로 뜨면 재기동마다 모든 사용자의 토큰이 무효가 된다 - 기동을 막는다
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("prod 에서는 JWT 키가 필요하다(JWT_PRIVATE_KEY · JWT_PUBLIC_KEY 또는 JWT_KEY_FILE)");
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            this.privateKey = pair.getPrivate();
            this.publicKey = pair.getPublic();
            log.warn("[JWT_EPHEMERAL_KEY] JWT 키 설정이 없어 임시 RSA 키 쌍을 생성했다 - 재기동하면 발급한 토큰이 모두 무효가 된다");
        } else {
            this.privateKey = loadPrivateKey(privatePem);
            this.publicKey = loadPublicKey(publicPem);
        }
        this.accessTokenValidityInMilliseconds = accessTokenValidity;
        this.refreshTokenValidityInMilliseconds = refreshTokenValidity;
    }

    // env 한 줄로 넣은 값을 PEM 으로 되돌린다 - 줄바꿈이 \n 문자열이면 실제 줄바꿈으로, 전체가 base64 로 감싸져 있으면 풀어서 쓴다
    private static String normalize(String value) {
        String text = value == null ? "" : value.replace("\\n", "\n").trim();
        if (!text.isEmpty() && !text.contains("-----BEGIN")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(text.replaceAll("\\s", "")), StandardCharsets.UTF_8);
                if (decoded.contains("-----BEGIN")) {
                    return decoded;
                }
            } catch (IllegalArgumentException ignored) {
                // base64 가 아니면 헤더 없는 키 본문(DER 의 base64) 그대로 쓴다
            }
        }
        return text;
    }

    // PEM 묶음 파일에서 "-----BEGIN <label>-----" ~ "-----END <label>-----" 블록 하나를 꺼낸다. 없으면 빈 문자열
    private static String extractBlock(String bundle, String label) {
        int start = bundle.indexOf("-----BEGIN " + label + "-----");
        int end = bundle.indexOf("-----END " + label + "-----");
        return start >= 0 && end > start ? bundle.substring(start, end + ("-----END " + label + "-----").length()) : "";
    }

    // Private Key 로드
    private PrivateKey loadPrivateKey(String privateKeyPem) throws Exception {
        String privateKeyContent = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(privateKeyContent);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }

    // Public Key 로드
    private PublicKey loadPublicKey(String publicKeyPem) throws Exception {
        String publicKeyContent = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(publicKeyContent);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(keySpec);
    }

    public String createAccessToken(String email, Role role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenValidityInMilliseconds);

        return Jwts.builder()
                .setSubject(email)
                .claim("role", role)
                .claim("type", "access")
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }


    public String createRefreshToken(String email, Role role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenValidityInMilliseconds);

        return Jwts.builder()
                .setSubject(email)
                .claim("role", role)
                .claim("type", "refresh")
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }


    public Authentication getAuthentication(String token) {
        String email = getEmail(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        return new UsernamePasswordAuthenticationToken(userDetails, "", userDetails.getAuthorities());
    }

    public String getEmail(String token) {
        return getClaims(token).getSubject();
    }

    public Role getRole(String token) {
        String role = getClaims(token).get("role", String.class);
        if (role == null) {
            throw new JwtException("토큰에서 역할 정보를 찾을 수 없습니다.");
        }
        return Role.valueOf(role);
    }

    // Token 타입 확인
    public boolean isAccessToken(String token) {
        return "access".equals(getClaims(token).get("type"));
    }

    public boolean isRefreshToken(String token) {
        return "refresh".equals(getClaims(token).get("type"));
    }


    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("만료된 JWT 토큰입니다.");
            throw new ExpiredJwtException(e.getHeader(), e.getClaims(), "만료된 토큰입니다.");
        } catch (UnsupportedJwtException e) {
            log.debug("지원되지 않는 JWT 토큰입니다.");
            throw new JwtException("인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
        } catch (MalformedJwtException e) {
            log.debug("잘못된 JWT 토큰입니다.");
            throw new JwtException("인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
        } catch (SignatureException e) {
            log.debug("JWT 서명이 일치하지 않습니다.");
            throw new JwtException("인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
        } catch (IllegalArgumentException e) {
            log.debug("JWT 토큰이 비어있습니다.");
            throw new JwtException("인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
        }
    }


    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public long getRefreshTokenValidityInMilliseconds() {
        return refreshTokenValidityInMilliseconds;
    }
}
