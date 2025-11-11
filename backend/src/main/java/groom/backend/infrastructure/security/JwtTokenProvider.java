package groom.backend.infrastructure.security;

import groom.backend.domain.auth.enums.Role;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    private final UserDetailsService userDetailsService;
    private final SecretKey secretKey;
    private final long accessTokenValidityInMilliseconds;
    private final long refreshTokenValidityInMilliseconds;

    public JwtTokenProvider(UserDetailsService userDetailsService,
            @Value("${jwt.security.secretKey}") String secret,
            @Value("${jwt.access.expiration}") long accessTokenValidity,
            @Value("${jwt.refresh.expiration}") long refreshTokenValidity) {
        this.userDetailsService = userDetailsService;
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenValidityInMilliseconds = accessTokenValidity;
        this.refreshTokenValidityInMilliseconds = refreshTokenValidity;
    }

    public String createAccessToken(String email, Role role) {
        return createToken(email, role, accessTokenValidityInMilliseconds);
    }

    public String createRefreshToken(String email, Role role) {
        return createToken(email, role, refreshTokenValidityInMilliseconds);
    }

    public Authentication getAuthentication(String token) {
        String email = getEmail(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        return new UsernamePasswordAuthenticationToken(userDetails, "", userDetails.getAuthorities());
    }

    private String createToken(String email, Role role, long validityInMilliseconds) {
        Claims claims = Jwts.claims().setSubject(email);
        claims.put("role", role.name());

        Date now = new Date();
        Date validity = new Date(now.getTime() + validityInMilliseconds);

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(validity)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public String getEmail(String token) {
        return getClaims(token).getSubject();
    }

    public Role getRole(String token) {
        String role = getClaims(token).get("role", String.class);
        return Role.valueOf(role);
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("만료된 JWT 토큰입니다.");
            throw new ExpiredJwtException(e.getHeader(), e.getClaims(), "만료된 토큰입니다.");
        } catch (UnsupportedJwtException e) {
            log.warn("지원되지 않는 JWT 토큰입니다.");
            throw new JwtException("인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
        } catch (MalformedJwtException e) {
            log.warn("잘못된 JWT 토큰입니다.");
            throw new JwtException("인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
        } catch (SignatureException e) {
            log.warn("JWT 서명이 일치하지 않습니다.");
            throw new JwtException("인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
        } catch (IllegalArgumentException e) {
            log.warn("JWT 토큰이 비어있습니다.");
            throw new JwtException("인증 정보가 유효하지 않습니다. 다시 로그인해 주세요.");
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public long getRefreshTokenValidityInMilliseconds() {
        return refreshTokenValidityInMilliseconds;
    }
}
