package com.imperium.distributed_lite_worker.security;

import com.imperium.distributed_lite_worker.config.WorkerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 校验调度器下发任务时携带的 {@code X-Worker-Token}。
 */
public class WorkerTokenFilter extends OncePerRequestFilter {

    public static final String WORKER_TOKEN_HEADER = "X-Worker-Token";

    private final WorkerProperties properties;

    public WorkerTokenFilter(WorkerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String configured = properties.getApi().getToken();
        if (!StringUtils.hasText(configured)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = request.getHeader(WORKER_TOKEN_HEADER);
        if (!configured.equals(token)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"success\":false,\"message\":\"Worker token invalid\"}");
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "worker", null, List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }
}
