package groom.backend.infrastructure.security;

import groom.backend.domain.auth.enums.Role;
import io.jsonwebtoken.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
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
                               @Value("${jwt.security.privateKey}") String  privateKeyPem,
                               @Value("${jwt.security.publicKey}") String  publicKeyPem,
                               @Value("${jwt.access.expiration}") long accessTokenValidity,
                               @Value("${jwt.refresh.expiration}") long refreshTokenValidity) throws Exception {
        this.userDetailsService = userDetailsService;
        this.privateKey = loadPrivateKey(privateKeyPem);
        this.publicKey = loadPublicKey(publicKeyPem);
        this.accessTokenValidityInMilliseconds = accessTokenValidity;
        this.refreshTokenValidityInMilliseconds = refreshTokenValidity;
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
