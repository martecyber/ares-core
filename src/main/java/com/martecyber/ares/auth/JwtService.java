package com.martecyber.ares.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtService(JwtProperties props) {
        this.props = props;
        if (props.getSecret() == null || props.getSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "ares.jwt.secret must be at least 32 bytes. Set ARES_JWT_SECRET or configure application-<profile>.yml");
        }
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(Long userId, String email, List<String> roles, List<String> permissions) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.getAccessTokenTtl());
        return Jwts.builder()
            .issuer(props.getIssuer())
            .subject(Long.toString(userId))
            .claim("email", email)
            .claim("roles", roles)
            .claim("permissions", permissions)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
            .verifyWith(key)
            .requireIssuer(props.getIssuer())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
