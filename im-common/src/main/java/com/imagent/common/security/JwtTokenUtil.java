package com.imagent.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * 统一 JWT 签发与验签工具（im-server 签发，im-gateway 握手验签）
 */
public class JwtTokenUtil {

    private final SecretKey secretKey;
    private final long expireSeconds;

    public JwtTokenUtil(String secret, long expireSeconds) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret 长度不能少于 32 字节（HS256 最低安全要求）");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expireSeconds = expireSeconds;
    }

    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, 1);
    }

    public String generateToken(Long userId, String username, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("tv", tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds)))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(String token) {
        Claims claims = parseAndValidate(token);
        return Long.valueOf(claims.getSubject());
    }

    public int extractTokenVersion(Claims claims) {
        Object tv = claims.get("tv");
        if (tv instanceof Number n) {
            return n.intValue();
        }
        return 1;
    }

    public int extractTokenVersion(String token) {
        return extractTokenVersion(parseAndValidate(token));
    }
}
