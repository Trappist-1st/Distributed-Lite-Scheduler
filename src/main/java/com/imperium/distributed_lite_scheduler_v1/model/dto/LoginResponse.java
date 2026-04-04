package com.imperium.distributed_lite_scheduler_v1.model.dto;

/**
 * 登录成功返回：访问令牌及元信息，便于前端存 token 并设置过期提醒。
 *
 * @param accessToken      JWT
 * @param tokenType        一般为 Bearer，与 Authorization 头约定一致
 * @param expiresInSeconds 多少秒后过期（与 jwt.expiration-ms 一致）
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
    public static final String BEARER = "Bearer";
}
