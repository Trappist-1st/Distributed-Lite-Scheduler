package com.imperium.distributed_lite_scheduler_v1.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * 签发与解析 JWT（HS256）。密钥长度须满足 HMAC-SHA256 要求（至少 256 bit）。
 */
@Component
public class JwtTokenProvider {

    public static final String CLAIM_TOKEN_TYPE = "token_type";
    public static final String TOKEN_TYPE_IDENTITY = "identity";
    public static final String TOKEN_TYPE_TENANT_ACCESS = "tenant_access";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_TENANT_CODE = "tenant_code";
    public static final String CLAIM_TENANT_ROLE = "tenant_role";

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("jwt.secret must be at least 32 bytes (UTF-8) for HS256");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    /** 访问令牌有效期（秒），供登录接口返回给客户端。 */
    public long getExpiresInSeconds() {
        return expirationMs / 1000;
    }

    /** 登录后的身份令牌（不含租户上下文）。 */
    public String createAccessToken(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_IDENTITY)
                .claim(CLAIM_USERNAME, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    /**
     * 租户上下文访问令牌：业务接口推荐携带此令牌，便于网关与业务层识别当前租户与成员角色。
     */
    public String createTenantAccessToken(
            Long userId,
            String username,
            Long tenantId,
            String tenantCode,
            String tenantRole) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_TENANT_ACCESS)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_TENANT_CODE, tenantCode != null ? tenantCode : "")
                .claim(CLAIM_TENANT_ROLE, tenantRole != null ? tenantRole : "")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
