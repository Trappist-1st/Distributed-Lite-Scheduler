package com.imperium.distributed_lite_scheduler_v1.security;

import tools.jackson.databind.json.JsonMapper;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 从 {@code Authorization: Bearer &lt;jwt&gt;} 解析身份，写入 {@link org.springframework.security.core.context.SecurityContext}。
 * 无 Bearer 头时不处理（由 Spring Security 判定匿名/未认证）；令牌无效时直接 401 JSON。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final JsonMapper jsonMapper;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, JsonMapper jsonMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            writeUnauthorized(response, "令牌不能为空");
            return;
        }

        try {
            Claims claims = jwtTokenProvider.parseClaims(token);
            Long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);
            JwtUserPrincipal principal = new JwtUserPrincipal(userId, username != null ? username : "");
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authentication.setDetails(new org.springframework.security.web.authentication.WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ExpiredJwtException e) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "登录已过期，请重新登录");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "令牌无效");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), Result.failure(ResultCode.UNAUTHORIZED, message));
    }
}
