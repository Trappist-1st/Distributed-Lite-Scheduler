package com.imperium.distributed_lite_scheduler_v1.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JWT 认证成功后的主体，放入 {@link org.springframework.security.core.context.SecurityContext}。
 * <p>
 * 身份令牌仅含 {@code userId/username}；租户令牌额外含 {@code tenantId/tenantCode/tenantRole}。
 */
public record JwtUserPrincipal(
        Long userId,
        String username,
        Long tenantId,
        String tenantCode,
        String tenantRole
) implements UserDetails {

    /** 无租户上下文时的便捷构造（与仅含 identity 的 JWT 一致）。 */
    public JwtUserPrincipal(Long userId, String username) {
        this(userId, username, null, null, null);
    }

    public boolean hasTenantContext() {
        return tenantId != null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> list = new ArrayList<>(2);
        list.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (tenantRole != null && !tenantRole.isBlank()) {
            list.add(new SimpleGrantedAuthority("ROLE_TENANT_" + tenantRole.trim()));
        }
        return List.copyOf(list);
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
