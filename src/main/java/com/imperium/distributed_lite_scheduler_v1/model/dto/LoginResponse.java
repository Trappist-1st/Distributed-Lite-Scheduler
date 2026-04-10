package com.imperium.distributed_lite_scheduler_v1.model.dto;

import java.util.List;

/**
 * 登录成功返回：访问令牌及元信息，便于前端存 token 并设置过期提醒。
 *
 * @param accessToken        JWT
 * @param tokenType          一般为 Bearer，与 Authorization 头约定一致
 * @param expiresInSeconds   多少秒后过期（与 jwt.expiration-ms 一致）
 * @param refreshToken       刷新令牌，未启用时为 {@code null}
 * @param tenants            可访问租户列表（仅含状态正常的租户）
 * @param defaultTenantId    建议默认租户：优先 OWNER，否则取列表首项；无租户时为 {@code null}
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String refreshToken,
        List<LoginTenantItem> tenants,
        Long defaultTenantId
) {
    public static final String BEARER = "Bearer";
}
