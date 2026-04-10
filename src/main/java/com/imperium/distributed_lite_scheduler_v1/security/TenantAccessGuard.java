package com.imperium.distributed_lite_scheduler_v1.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TenantMemberMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TenantMember;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 多租户访问守卫：统一处理“当前登录用户 / 租户上下文 / 成员关系 / 角色校验”。
 * 目标是避免每个 Service 重复写同样的鉴权模板，减少漏判风险。
 */
@Component
public class TenantAccessGuard {
    public static final String MSG_UNAUTHORIZED = "未登录";
    public static final String MSG_SWITCH_TENANT = "请先切换到目标租户";
    public static final String MSG_TENANT_ACCESS_DENIED = "无权访问当前租户资源";

    private final TenantMemberMapper tenantMemberMapper;

    public TenantAccessGuard(TenantMemberMapper tenantMemberMapper) {
        this.tenantMemberMapper = tenantMemberMapper;
    }

    public Result<JwtUserPrincipal> requireLogin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtUserPrincipal principal)) {
            return Result.failure(ResultCode.UNAUTHORIZED, MSG_UNAUTHORIZED);
        }
        return Result.success(principal);
    }

    /**
     * 需要租户上下文 + 需要是租户成员；如指定 allowedRoles，则要求角色命中。
     */
    public Result<AccessContext> requireTenantMember(Set<String> allowedRoles, String roleDeniedMessage) {
        Result<JwtUserPrincipal> login = requireLogin();
        if (!login.isSuccess()) {
            return Result.failure(login.getCode(), login.getMessage());
        }
        JwtUserPrincipal principal = login.getData();
        if (!principal.hasTenantContext()) {
            return Result.failure(ResultCode.BAD_REQUEST, MSG_SWITCH_TENANT);
        }

        TenantMember membership = tenantMemberMapper.selectOne(
                new LambdaQueryWrapper<TenantMember>()
                        .eq(TenantMember::getTenantId, principal.tenantId())
                        .eq(TenantMember::getUserId, principal.userId()));
        if (membership == null) {
            return Result.failure(ResultCode.FORBIDDEN, MSG_TENANT_ACCESS_DENIED);
        }

        String role = normalizeRole(membership.getRole());
        if (allowedRoles != null && !allowedRoles.isEmpty() && !allowedRoles.contains(role)) {
            return Result.failure(ResultCode.FORBIDDEN, roleDeniedMessage);
        }
        return Result.success(new AccessContext(principal, membership, role));
    }

    public String normalizeRole(String role) {
        return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    }

    public record AccessContext(
            JwtUserPrincipal principal,
            TenantMember membership,
            String role
    ) {
    }
}
