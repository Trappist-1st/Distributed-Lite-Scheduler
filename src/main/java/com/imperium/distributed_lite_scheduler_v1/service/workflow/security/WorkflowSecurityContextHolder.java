package com.imperium.distributed_lite_scheduler_v1.service.workflow.security;

import com.imperium.distributed_lite_scheduler_v1.security.TenantAccessGuard;

/**
 * 工作流切面鉴权通过后，将 {@link TenantAccessGuard.AccessContext} 绑定到当前线程，
 * 供 {@code WorkflowServiceImpl} 读取租户/用户，避免重复调用 {@code requireTenantMember}。
 */
public final class WorkflowSecurityContextHolder {

    private static final ThreadLocal<TenantAccessGuard.AccessContext> CONTEXT = new ThreadLocal<>();

    private WorkflowSecurityContextHolder() {
    }

    public static void set(TenantAccessGuard.AccessContext context) {
        CONTEXT.set(context);
    }

    public static TenantAccessGuard.AccessContext require() {
        TenantAccessGuard.AccessContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new IllegalStateException("缺少工作流安全上下文，请确认方法已标注 @RequireWorkflowPermission");
        }
        return ctx;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
